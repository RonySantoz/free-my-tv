package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prices of two B3 funds (brapi.dev, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * The fund prices need the brapi key (brapi_key in config.properties); Open-Meteo needs none. Location and currency
 * come from config.properties.
 */
final class HomeData {
    /**
     * The quotes on the home screen, in this order: AUPO11 (fixed income) and ABTC11 (Bitcoin ETF), the Ibovespa, the
     * dollar, the Selic rate and Bitcoin in dollars. Drawn as their tickers (Glyphs), BTC with its logo.
     */
    static final String[] COINS = {"AUPO11", "ABTC11", "IBOV", "USD", "SELIC", "BTC"};
    static final String[] COIN_NAMES = {"Fixed income", "Bitcoin ETF", "Ibovespa", "Dollar", "Selic", "Bitcoin"};
    private static final String CURRENCY = Place.currency();

    // Both forecasts in one answer, the next 48 hours and today plus 5 days: Home Settings → Forecast switches without
    // asking again, and offline too.
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,weather_code"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=6&forecast_hours=48";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;

    /** Indexes in COINS of the funds the user has on. */
    static int[] active() {
        int[] a = activeCoins;
        if (a != null && activeVersion == Prefs.version) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i)) tmp[n++] = i;
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
        pricesFailedAt = 0;
        weatherFailedAt = 0;
    }

    static String coinName(int i) {
        return Ui.tr(COIN_NAMES[i]);
    }

    /**
     * Every quote, as one JSON object by index: {"p": price, "c": change over 7 days in %}. Each source is asked on its
     * own, so one that fails leaves only its own quotes out. brapi.dev answers one ticker per request (a list is refused
     * with HTTP 400) and takes the key in the Authorization header, never in the address.
     */
    private static String getPrices() throws Exception {
        JSONObject out = new JSONObject();
        for (int k = 0; k < 2; k++) {
            try {
                fundQuote(out, k, COINS[k]);
            } catch (Exception e) {
                AppLog.w("prices " + COINS[k] + ": " + e.getMessage());
            }
        }
        try {
            fundQuote(out, 2, "%5EBVSP");
        } catch (Exception e) {
            AppLog.w("prices IBOV: " + e.getMessage());
        }
        try {
            // The dollar's official rate (PTAX, Banco Central): the last business days, the first one for the change.
            JSONArray ptax = new JSONArray(get("https://api.bcb.gov.br/dados/serie/bcdata.sgs.1/dados/ultimos/6?formato=json"));
            put(out, 3, value(ptax, "valor"), changeOf(ptax, "valor"));
        } catch (Exception e) {
            AppLog.w("prices USD: " + e.getMessage());
        }
        try {
            // The Selic target rate changes only at the Copom meetings, so it has no change to show.
            JSONArray selic = new JSONArray(get("https://api.bcb.gov.br/dados/serie/bcdata.sgs.432/dados/ultimos/1?formato=json"));
            put(out, 4, value(selic, "valor"), Double.NaN);
        } catch (Exception e) {
            AppLog.w("prices Selic: " + e.getMessage());
        }
        try {
            // Bitcoin in dollars, daily candles: the last one is today's price, the first one is a week back.
            JSONArray candles = new JSONArray(get("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=8"));
            double first = Double.parseDouble(candles.getJSONArray(0).getString(4));
            double last = Double.parseDouble(candles.getJSONArray(candles.length() - 1).getString(4));
            put(out, 5, last, (last - first) / first * 100);
        } catch (Exception e) {
            AppLog.w("prices BTC: " + e.getMessage());
        }
        return out.toString();
    }

    /** A fund (or the Ibovespa) from brapi.dev: today's price and the change since the first day of the last 5 trading days. */
    private static void fundQuote(JSONObject out, int k, String symbol) throws Exception {
        JSONObject q = new JSONObject(get("https://brapi.dev/api/quote/" + symbol + "?range=5d&interval=1d", "Bearer " + Config.BRAPI_KEY))
                .getJSONArray("results").getJSONObject(0);
        double price = q.getDouble("regularMarketPrice");
        double change = Double.NaN;
        JSONArray history = q.optJSONArray("historicalDataPrice");
        if (history != null && history.length() > 0) {
            double first = history.getJSONObject(0).optDouble("close", Double.NaN);
            if (first > 0) change = (price - first) / first * 100;
        }
        put(out, k, price, change);
    }

    private static void put(JSONObject out, int k, double price, double change) throws JSONException {
        JSONObject o = new JSONObject().put("p", price);
        if (!Double.isNaN(change)) o.put("c", change);
        out.put(String.valueOf(k), o);
    }

    /** The last reading of a Banco Central series (its values are text, with a point as the decimal mark). */
    private static double value(JSONArray series, String field) throws JSONException {
        return Double.parseDouble(series.getJSONObject(series.length() - 1).get(field).toString());
    }

    private static double changeOf(JSONArray series, String field) throws JSONException {
        double first = Double.parseDouble(series.getJSONObject(0).get(field).toString());
        double last = value(series, field);
        return (last - first) / first * 100;
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
        // The next 5 days (from tomorrow): noon of each day on the TV's clock, high, low and code.
        final List<Long> days = new ArrayList<>();
        final List<Double> dayMax = new ArrayList<>();
        final List<Double> dayMin = new ArrayList<>();
        final List<Integer> dayCode = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;
    private volatile long pricesFailedAt, weatherFailedAt;
    /** The city of the forecast in memory: another process (ScreensaverAlone) can see the city change under it. */
    private volatile String weatherFor = "";

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            if (Prefs.quotes()) {
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                // Fetched with another query (an older version): shown, and fetched again now.
                weatherAt = WEATHER_QUERY.equals(cache.getString("weatherQuery", "")) ? cache.getLong("weatherAt", 0) : 0;
                weatherFor = weatherPlace();
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt) && !failedRecently(pricesFailedAt, now)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body = getPrices();
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    pricesFailedAt = System.currentTimeMillis();
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        boolean moved = !weatherPlace().equals(weatherFor);
        if (!fetchingWeather && (moved || now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String place = weatherPlace();
                    String body = get(weatherUrl());
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    weatherFor = place;
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", place)
                            .putString("weatherQuery", WEATHER_QUERY).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        int found = 0;
        for (int k = 0; k < COINS.length; k++) {
            JSONObject q = o.optJSONObject(String.valueOf(k));
            if (q == null) {   // this source didn't answer: its quote shows as a dash
                prices[k] = Double.NaN;
                changes[k] = Double.NaN;
                continue;
            }
            found++;
            prices[k] = q.getDouble("p");
            changes[k] = q.optDouble("c", Double.NaN);
        }
        if (found == 0) pricesAt = 0;   // nothing came back: fetch again
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        // Open-Meteo writes every time at the place's offset of now, even past a DST change (Berlin's sunrise after the
        // change still reads 07:51 at GMT+2): read them at that offset, as LG and Samsung do, not by the zone's rules.
        TimeZone zone = o.has("utc_offset_seconds") ? new SimpleTimeZone(o.getInt("utc_offset_seconds") * 1000, "GMT")
                : TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        JSONArray dates = daily.getJSONArray("time"), highs = daily.getJSONArray("temperature_2m_max"),
                lows = daily.getJSONArray("temperature_2m_min"), codes = daily.optJSONArray("weather_code");
        long now = System.currentTimeMillis();
        for (int i = 0; codes != null && i < dates.length() && w.days.size() < 5; i++) {
            String date = dates.getString(i);
            // Only days that haven't started at the city: today, and past days in a forecast kept offline, are left out.
            if (localTime(date + "T12:00", zone) - 12 * 3600_000L <= now) continue;
            w.days.add(localTime(date + "T12:00", TimeZone.getDefault()));   // its noon on the TV's clock: named right anywhere
            w.dayMax.add(highs.optDouble(i));
            w.dayMin.add(lows.optDouble(i));
            w.dayCode.add(codes.optInt(i, -1));
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");   // Open-Meteo takes the language without the country
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        return get(url, null);
    }

    private static String get(String url, String authorization) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        return isNight(Prefs.theme(), now, weather.sunrise, weather.sunset);
    }

    /** The same from what it depends on (SleepOverlay gets these in its Intent). */
    static boolean isNight(String theme, long now, long sunrise, long sunset) {
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 4.15M · 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 1_000_000) return String.format(locale(), "%.2fM", v / 1_000_000);
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 4.15M · 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 1_000_000) return String.format(locale(), "%.2fM", v / 1_000_000);   // Bitcoin in rubles…
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /**
     * $108,234 · $185.20 · R$ 581.234 · 74 264 € (cents only below a thousand), in the configured currency, with the
     * symbol and spaces where the language puts them.
     */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> {
            DecimalFormat f = (DecimalFormat) NumberFormat.getCurrencyInstance(locale());
            DecimalFormatSymbols s = f.getDecimalFormatSymbols();
            s.setCurrencySymbol(symbol.trim());   // our symbols: ICU would write "US$" outside the US
            f.setDecimalFormatSymbols(s);
            f.setMinimumFractionDigits(v >= 1000 ? 0 : 2);
            f.setMaximumFractionDigits(v >= 1000 ? 0 : 2);
            return f.format(v);
        });
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? Ui.tr("Clear") : Ui.tr("night|Clear");
        if (code == 1) return day ? Ui.tr("Mostly Sunny") : Ui.tr("Mostly Clear");
        if (code == 2) return Ui.tr("Partly Cloudy");
        if (code == 3) return Ui.tr("Cloudy");
        if (code == 45 || code == 48) return Ui.tr("Fog");
        if (code >= 51 && code <= 57) return Ui.tr("Drizzle");
        if (code == 65 || code == 67 || code == 82) return Ui.tr("Heavy Rain");
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Ui.tr("Snow");
        if (code >= 95) return Ui.tr("Thunderstorms");
        return Ui.tr("Rain");
    }

    /** "H:24°  L:15°" (in Portuguese, "Máx. 24°  Mín. 15°"), with the given space between the two. */
    static String highLow(Weather wx, String space) {
        return Ui.trf("H:{max}", "max", degrees(wx.max)) + space + Ui.trf("L:{min}", "min", degrees(wx.min));
    }

    private static String currencySymbol() {
        switch (CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign); "+1,2 %" in the languages that space the percent sign. */
    /** The 7-day change with an arrow: "↑ 1,2%" or "↓ 0,8%". */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> {
            NumberFormat f = NumberFormat.getPercentInstance(locale());
            f.setMinimumFractionDigits(1);
            f.setMaximumFractionDigits(1);
            return (v >= 0 ? "↑ " : "↓ ") + f.format(Math.abs(v) / 100);
        });
    }

    /** The arrow of the 7-day change, for the quotes row: " ↑" or " ↓", nothing without a change. */
    static String arrow(double v) {
        if (Double.isNaN(v)) return "";
        return v >= 0 ? " ↑" : " ↓";
    }

    /** A quote in its own unit: R$ with cents for the funds and the dollar, points for the Ibovespa, % for the Selic, US$ for Bitcoin. */
    static String quote(int k, double v) {
        if (Double.isNaN(v)) return "—";
        switch (COINS[k]) {
            case "IBOV": return number(v, 0) + " pts";
            case "SELIC": return number(v, 2) + "%";
            case "BTC": return "US$ " + number(v, 0);
            default: return "R$ " + number(v, 2);
        }
    }

    private static String number(double v, int digits) {
        NumberFormat f = NumberFormat.getNumberInstance(locale());
        f.setMinimumFractionDigits(digits);
        f.setMaximumFractionDigits(digits);
        return f.format(v);
    }

    static String hour(long at) {
        return Ui.once(Prefs.clock24() ? "hour24" : "hour", at, () -> Ui.datePattern("format|h a").format(at));
    }

    /** "Seg", "Mon", "Lun", "Mo", "Пн": the short day of the week, capitalized and without the abbreviation's dot. */
    static String weekday(long at) {
        return Ui.once("weekday", at, () -> {
            String s = Ui.datePattern("format|EEE").format(at);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
            return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Ui.locale()) + s.substring(1);
        });
    }

    /** "Sunday", "domingo", "Sonntag": a day's full name, for TalkBack. */
    static String weekdayName(long at) {
        return Ui.once("weekdayName", at, () -> new SimpleDateFormat("EEEE", Ui.locale()).format(new java.util.Date(at)));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        return minutes < 60 ? Ui.trf("{n} min ago", "n", minutes) : Ui.trf("{n} h ago", "n", minutes / 60);
    }
}

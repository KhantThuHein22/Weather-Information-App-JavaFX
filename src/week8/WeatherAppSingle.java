package week8;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class WeatherAppSingle extends Application {

    // --- CONFIGURATION ---
    // Default API key (you provided). It's more secure to set OWM_API_KEY environment variable instead.
    private static final String DEFAULT_API_KEY = "87fe6eaadd0b3ed0bbad6cc533ebcb60";

    // OpenWeatherMap endpoints
    private static final String CUR_WEATHER_ENDPOINT = "https://api.openweathermap.org/data/2.5/weather";
    private static final String FORECAST_ENDPOINT = "https://api.openweathermap.org/data/2.5/forecast";
    private static final String ICON_URL_TEMPLATE = "https://openweathermap.org/img/wn/%s@2x.png";

    // UI Controls
    private TextField txtCity;
    private Button btnSearch;
    private ChoiceBox<String> cbTempUnit;
    private ChoiceBox<String> cbWindUnit;
    private VBox currentBox;
    private HBox forecastBox;
    private ListView<String> historyList;

    private BorderPane root;

    // History mapping: displayString -> Stored CurrentWeather
    private final LinkedHashMap<String, CurrentWeather> historyMap = new LinkedHashMap<>();

    // HTTP client
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // Date/time formatter
    private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Executor for background tasks
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    // --- Data containers ---
    private static class CurrentWeather {
        String cityName = "";
        long timestamp = 0L;
        double tempC = 0.0;
        double tempF = 0.0;
        int humidity = 0;
        double windSpeed = 0.0; // as returned by API (interpreted later)
        String description = "";
        String icon = "";
        long sunrise = 0L;
        long sunset = 0L;
    }

    private static class ForecastEntry {
        long timestamp;
        double tempC;
        double tempF;
        String description;
        String icon;
    }

    // --- JavaFX Application start ---
    @Override
    public void start(Stage stage) {
        String apiKey = System.getenv("OWM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = DEFAULT_API_KEY;
        // store API key in a final variable closure used inside worker thread
        final String OPENWEATHER_API_KEY = apiKey;

        // Top controls
        Label lblCity = new Label("City:");
        txtCity = new TextField();
        txtCity.setPromptText("e.g., Yangon, London");
        btnSearch = new Button("Get Weather");
        cbTempUnit = new ChoiceBox<>();
        cbTempUnit.getItems().addAll("Celsius (°C)", "Fahrenheit (°F)");
        cbTempUnit.setValue("Celsius (°C)");
        cbWindUnit = new ChoiceBox<>();
        cbWindUnit.getItems().addAll("m/s", "mph");
        cbWindUnit.setValue("m/s");

        HBox top = new HBox(8, lblCity, txtCity, btnSearch, new Label("Temp:"), cbTempUnit, new Label("Wind:"), cbWindUnit);
        top.setPadding(new Insets(10));
        top.setAlignment(Pos.CENTER_LEFT);

        // Current weather display
        currentBox = new VBox(8);
        currentBox.setPadding(new Insets(12));
        currentBox.setPrefHeight(200);
        currentBox.setStyle("-fx-background-color: rgba(255,255,255,0.8); -fx-background-radius: 8;");

        // Forecast display
        forecastBox = new HBox(10);
        forecastBox.setPadding(new Insets(10));

        VBox center = new VBox(10, currentBox, new Label("Short-term Forecast:"), forecastBox);
        center.setPadding(new Insets(10));

        // History
        historyList = new ListView<>();
        historyList.setPrefWidth(260);
        historyList.setPrefHeight(360);
        VBox right = new VBox(8, new Label("Search History (click to re-run):"), historyList);
        right.setPadding(new Insets(10));

        root = new BorderPane();
        root.setTop(top);
        root.setCenter(center);
        root.setRight(right);
        applyDefaultBackground();

        // Event handlers
        btnSearch.setOnAction(ev -> runSearch(OPENWEATHER_API_KEY));
        txtCity.setOnAction(ev -> runSearch(OPENWEATHER_API_KEY));
        cbTempUnit.setOnAction(ev -> {
            // re-request (to fetch units directly from API) — simpler and accurate
            if (!historyList.getItems().isEmpty()) {
                String last = historyList.getItems().get(0);
                String city = extractCityFromHistoryLabel(last);
                txtCity.setText(city);
                runSearch(OPENWEATHER_API_KEY);
            }
        });

        historyList.setOnMouseClicked(ev -> {
            String sel = historyList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                String city = extractCityFromHistoryLabel(sel);
                txtCity.setText(city);
                runSearch(OPENWEATHER_API_KEY);
            }
        });

        Scene scene = new Scene(root, 980, 600);
        stage.setTitle("Weather Information App");
        stage.setScene(scene);
        stage.show();
    }

    private void runSearch(String apiKey) {
        String city = txtCity.getText().trim();
        if (city.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Input required", "Please enter a city name (e.g., Yangon).");
            return;
        }
        String units = cbTempUnit.getValue().startsWith("Celsius") ? "metric" : "imperial";

        btnSearch.setDisable(true);
        currentBox.getChildren().setAll(new Label("Loading..."));
        forecastBox.getChildren().clear();

        // fetch current + forecast in background
        executor.submit(() -> {
            try {
                CurrentWeather cw = fetchCurrentWeather(city, units, apiKey);
                List<ForecastEntry> forecast = fetchForecast(city, units, apiKey, 5);

                Platform.runLater(() -> {
                    displayCurrentWeather(cw);
                    displayForecast(forecast);
                    addHistoryEntry(cw);
                    applyBackgroundForTimes(cw);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Error", ex.getMessage()));
            } finally {
                Platform.runLater(() -> btnSearch.setDisable(false));
            }
        });
    }

    // --- Networking & minimal JSON parsing (no external libs) ---

    // Fetch current weather JSON and parse relevant fields
    private CurrentWeather fetchCurrentWeather(String city, String units, String apiKey) throws IOException, InterruptedException {
        String q = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String url = CUR_WEATHER_ENDPOINT + "?q=" + q + "&units=" + units + "&appid=" + apiKey;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        String body = resp.body();
        if (status != 200) {
            // try to extract message from JSON
            String msg = extractJsonString(body, "message");
            if (msg == null || msg.isEmpty()) msg = "HTTP " + status + " returned from API.";
            throw new IOException("API error: " + msg);
        }

        // parse minimal JSON fields safely
        CurrentWeather cw = new CurrentWeather();
        cw.cityName = extractJsonString(body, "name");
        cw.timestamp = safeLong(extractJsonNumber(body, "dt"), Instant.now().getEpochSecond());
        cw.sunrise = safeLong(extractNestedNumber(body, "sys", "sunrise"), 0L);
        cw.sunset = safeLong(extractNestedNumber(body, "sys", "sunset"), 0L);

        double temp = safeDouble(extractNestedNumber(body, "main", "temp"), 0.0);
        if ("imperial".equals(units)) {
            cw.tempF = temp;
            cw.tempC = (temp - 32.0) * 5.0 / 9.0;
        } else {
            cw.tempC = temp;
            cw.tempF = temp * 9.0 / 5.0 + 32.0;
        }
        cw.humidity = (int) safeLong(extractNestedNumber(body, "main", "humidity"), 0L);

        // wind
        cw.windSpeed = safeDouble(extractNestedNumber(body, "wind", "speed"), 0.0);

        // weather array: description and icon
        cw.description = extractFromFirstArray(body, "weather", "description");
        cw.icon = extractFromFirstArray(body, "weather", "icon");

        return cw;
    }

    // Fetch forecast entries (first N items)
    private List<ForecastEntry> fetchForecast(String city, String units, String apiKey, int limit) throws IOException, InterruptedException {
        String q = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String url = FORECAST_ENDPOINT + "?q=" + q + "&units=" + units + "&appid=" + apiKey;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        String body = resp.body();
        if (status != 200) {
            String msg = extractJsonString(body, "message");
            if (msg == null || msg.isEmpty()) msg = "HTTP " + status + " returned from API (forecast).";
            throw new IOException("API error: " + msg);
        }

        List<String> items = extractArrayItems(body, "list");
        List<ForecastEntry> result = new ArrayList<>();
        for (int i = 0; i < items.size() && result.size() < limit; i++) {
            String item = items.get(i);
            ForecastEntry fe = new ForecastEntry();
            fe.timestamp = safeLong(extractJsonNumber(item, "dt"), 0L);
            double temp = safeDouble(extractNestedNumber(item, "main", "temp"), 0.0);
            if ("imperial".equals(cbTempUnit.getValue().startsWith("Celsius") ? "metric" : "imperial")) {
                // this branch shouldn't normally happen since units param was passed earlier; keep simple
                fe.tempF = temp;
                fe.tempC = (temp - 32.0) * 5.0 / 9.0;
            } else {
                fe.tempC = temp;
                fe.tempF = temp * 9.0 / 5.0 + 32.0;
            }
            fe.description = extractFromFirstArray(item, "weather", "description");
            fe.icon = extractFromFirstArray(item, "weather", "icon");
            result.add(fe);
        }
        return result;
    }

    // ----- Minimal JSON helpers (ad-hoc but robust enough for OWM responses) -----

    // Extract a top-level string field: "name":"Yangon"
    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return "";
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return "";
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return "";
        return json.substring(firstQuote + 1, secondQuote);
    }

    // Extract a top-level numeric token like "dt":163... returns substring (digits, decimal, -)
    private static String extractJsonNumber(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int i = colon + 1;
        // skip whitespace
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        // accept digits, minus, decimal
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.' || json.charAt(i)=='-' || json.charAt(i)=='e' || json.charAt(i)=='E' || json.charAt(i)=='+')) i++;
        if (start == i) return null;
        return json.substring(start, i);
    }

    // Extract nested number like "main": { "temp": ... }
    private static String extractNestedNumber(String json, String parentKey, String childKey) {
        String parentPattern = "\"" + parentKey + "\"";
        int p = json.indexOf(parentPattern);
        if (p < 0) return null;
        int brace = json.indexOf('{', p + parentPattern.length());
        if (brace < 0) return null;
        // find matching closing brace for parent object (simple scanning)
        int depth = 0;
        int end = -1;
        for (int i = brace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        if (end < 0) return null;
        String parentBody = json.substring(brace, end + 1);
        return extractJsonNumber(parentBody, childKey);
    }

    // Extract value (string) from first object of an array, e.g., first element of "weather":[{ "description":"..." }]
    private static String extractFromFirstArray(String json, String arrayKey, String childKey) {
        String arrPattern = "\"" + arrayKey + "\"";
        int idx = json.indexOf(arrPattern);
        if (idx < 0) return "";
        int bracket = json.indexOf('[', idx + arrPattern.length());
        if (bracket < 0) return "";
        // find end of first object (search for '{' and matching '}')
        int objStart = json.indexOf('{', bracket);
        if (objStart < 0) return "";
        int depth = 0;
        int objEnd = -1;
        for (int i = objStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { objEnd = i; break; }
            }
        }
        if (objEnd < 0) return "";
        String obj = json.substring(objStart, objEnd + 1);
        return extractJsonString(obj, childKey);
    }

    // Extract items of a top-level array like "list": [ {...}, {...}, ... ]
    private static List<String> extractArrayItems(String json, String arrayKey) {
        List<String> items = new ArrayList<>();
        String arrPattern = "\"" + arrayKey + "\"";
        int idx = json.indexOf(arrPattern);
        if (idx < 0) return items;
        int bracket = json.indexOf('[', idx + arrPattern.length());
        if (bracket < 0) return items;
        int i = bracket + 1;
        // parse top-level array objects (assumes array of objects)
        while (i < json.length()) {
            // skip whitespace and commas
            while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= json.length()) break;
            if (json.charAt(i) == ']') break;
            if (json.charAt(i) != '{') { i++; continue; }
            int start = i;
            int depth = 0;
            int end = -1;
            for (int j = i; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = j; break; }
                }
            }
            if (end < 0) break;
            String obj = json.substring(start, end + 1);
            items.add(obj);
            i = end + 1;
        }
        return items;
    }

    // safe numeric conversions
    private static long safeLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            // remove quotes if present
            s = s.replaceAll("\"", "");
            if (s.contains(".")) s = s.substring(0, s.indexOf('.'));
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
    private static double safeDouble(String s, double fallback) {
        if (s == null) return fallback;
        try {
            s = s.replaceAll("\"", "");
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ----- UI rendering -----

    private void displayCurrentWeather(CurrentWeather cw) {
        currentBox.getChildren().clear();
        Label title = new Label(cw.cityName + "  (as of " + Instant.ofEpochSecond(cw.timestamp).atZone(ZoneId.systemDefault()).format(tsFmt) + ")");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));

        ImageView iconView = new ImageView();
        if (cw.icon != null && !cw.icon.isBlank()) {
            String iconUrl = String.format(ICON_URL_TEMPLATE, cw.icon);
            try {
                Image img = new Image(iconUrl, true);
                iconView.setImage(img);
                iconView.setFitWidth(80);
                iconView.setFitHeight(80);
            } catch (Exception ignored) { }
        }

        boolean showC = cbTempUnit.getValue().startsWith("Celsius");
        double tempToShow = showC ? cw.tempC : cw.tempF;
        String tempStr = String.format("%.1f °%s", tempToShow, showC ? "C" : "F");

        // Wind unit handling:
        boolean wantMps = cbWindUnit.getValue().equals("m/s");
        double windValue = cw.windSpeed;
        // Note: OpenWeatherMap returns wind speed in m/s for 'metric' and in miles/hour for 'imperial' historically; in practice OWM returns m/s regardless in many endpoints.
        // To be safe, convert based on the temperature unit selected earlier: if user selected Fahrenheit we requested imperial from API, so treat windValue as mph.
        if (cbTempUnit.getValue().startsWith("Celsius")) {
            // assume windValue in m/s
            if (!wantMps) windValue = windValue * 2.23694; // m/s -> mph
        } else {
            // assume windValue in mph
            if (wantMps) windValue = windValue * 0.44704; // mph -> m/s
        }

        String windStr = String.format("%.2f %s", windValue, wantMps ? "m/s" : "mph");

        Label lblTemp = new Label("Temperature: " + tempStr);
        Label lblDesc = new Label("Condition: " + (cw.description == null ? "" : capitalize(cw.description)));
        Label lblHumidity = new Label("Humidity: " + cw.humidity + " %");
        Label lblWind = new Label("Wind: " + windStr);

        VBox info = new VBox(6, title, lblTemp, lblDesc, lblHumidity, lblWind);
        info.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(12, iconView, info);
        row.setAlignment(Pos.CENTER_LEFT);

        currentBox.getChildren().add(row);
    }

    private void displayForecast(List<ForecastEntry> forecast) {
        forecastBox.getChildren().clear();
        boolean showC = cbTempUnit.getValue().startsWith("Celsius");
        for (ForecastEntry fe : forecast) {
            VBox card = new VBox(6);
            card.setPadding(new Insets(8));
            card.setPrefWidth(140);
            card.setStyle("-fx-background-color: rgba(255,255,255,0.9); -fx-background-radius: 6;");
            card.setAlignment(Pos.CENTER);

            String time = Instant.ofEpochSecond(fe.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            Label lblTime = new Label(time);
            ImageView iv = new ImageView();
            if (fe.icon != null && !fe.icon.isBlank()) {
                try {
                    Image img = new Image(String.format(ICON_URL_TEMPLATE, fe.icon), true);
                    iv.setImage(img);
                    iv.setFitWidth(60);
                    iv.setFitHeight(60);
                } catch (Exception ignored) {}
            }
            double temp = showC ? fe.tempC : fe.tempF;
            Label lblTemp = new Label(String.format("%.1f °%s", temp, showC ? "C" : "F"));
            Label lblDesc = new Label(capitalize(fe.description));
            lblDesc.setWrapText(true);
            lblDesc.setTextAlignment(TextAlignment.CENTER);
            card.getChildren().addAll(lblTime, iv, lblTemp, lblDesc);
            forecastBox.getChildren().add(card);
        }
    }

    private void addHistoryEntry(CurrentWeather cw) {
        String display = cw.cityName + " - " + Instant.ofEpochSecond(cw.timestamp).atZone(ZoneId.systemDefault()).format(tsFmt);
        // keep only unique most recent - remove existing same city entries
        historyMap.entrySet().removeIf(e -> e.getKey().startsWith(cw.cityName + " - "));
        historyMap.put(display, cw);
        // refresh ListView with newest at top
        List<String> items = new ArrayList<>(historyMap.keySet());
        Collections.reverse(items);
        historyList.getItems().setAll(items);
        // limit to last 15 entries
        if (historyList.getItems().size() > 15) {
            historyList.getItems().remove(15, historyList.getItems().size());
        }
    }

    // Background selection based on sunrise/sunset when available
    private void applyBackgroundForTimes(CurrentWeather cw) {
        if (cw.sunrise > 0 && cw.sunset > 0) {
            long now = Instant.now().getEpochSecond();
            boolean isDay = now >= cw.sunrise && now < cw.sunset;
            if (isDay) {
                root.setBackground(new Background(new BackgroundFill(Color.web("#87CEEB"), CornerRadii.EMPTY, Insets.EMPTY))); // light sky
            } else {
                root.setBackground(new Background(new BackgroundFill(Color.web("#1b1f3b"), CornerRadii.EMPTY, Insets.EMPTY))); // night
            }
        } else {
            applyDefaultBackground();
        }
    }

    private void applyDefaultBackground() {
        int hour = LocalTime.now().getHour();
        if (hour >= 6 && hour < 18) {
            root.setBackground(new Background(new BackgroundFill(Color.web("#a3d9ff"), CornerRadii.EMPTY, Insets.EMPTY)));
        } else if (hour >= 18 && hour < 20) {
            root.setBackground(new Background(new BackgroundFill(Color.web("#ffb35e"), CornerRadii.EMPTY, Insets.EMPTY)));
        } else {
            root.setBackground(new Background(new BackgroundFill(Color.web("#1b1f3b"), CornerRadii.EMPTY, Insets.EMPTY)));
        }
    }

    // --- Utility helpers ---
    private static String extractCityFromHistoryLabel(String label) {
        // label format: "CityName - yyyy-MM-dd HH:mm:ss"
        int idx = label.indexOf(" - ");
        if (idx < 0) return label;
        return label.substring(0, idx);
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void showAlert(Alert.AlertType type, String title, String msg) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    @Override
    public void stop() {
        executor.shutdownNow();
    }

    // --- Main ---
    public static void main(String[] args) {
        launch(args);
    }
}


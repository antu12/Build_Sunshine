package com.example.arshad.sunshine.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.text.format.Time;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private final String LOG_TAG =  MainActivity.class.getSimpleName();
    ArrayAdapter mForcastAdapter;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);



         mForcastAdapter = new ArrayAdapter<String>(
                getApplicationContext(),
                R.layout.list_item_forecast,
                R.id.list_item_forecast_textview,
                new ArrayList<String>());
        ListView listView = (ListView) findViewById(R.id.listview_forecast);
        listView.setAdapter(mForcastAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String text = (String) mForcastAdapter.getItem(position);
                Intent intent = new Intent(getApplicationContext(), DetailsActivity.class).putExtra(Intent.EXTRA_TEXT, text);
                startActivity(intent);

            }
        });



    }

    @Override
    protected void onStart() {
        super.onStart();
        updateWeather();
    }

    //making the detail available
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.detail, menu);
        return true;
    }

    String AppId = "820740a5e8c9759ce25fbf27676d9876";
    //getting the id and verifying it
    @Override
    public boolean onOptionsItemSelected(MenuItem item){

        int id = item.getItemId();
        if (id == R.id.action_refresh){
            updateWeather();
            return true;
        } else if (id == R.id.action_settings){
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        return  super.onOptionsItemSelected(item);
    }

    private void openPreferredLocationInMap(){
        String location = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(
                getString(R.string.pref_location_key), getString(R.string.pref_location_default));
        Uri geoLocation = Uri.parse("geo:0,0?").buildUpon()
                .appendQueryParameter("q", location).build();
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(geoLocation);
        if (intent.resolveActivity(getPackageManager()) != null){
            startActivity(intent);
        }else {
            Log.d(LOG_TAG, "Location not found!");
        }
    }

    private void updateWeather() {
        fetchWeatherTask weatherTask = new fetchWeatherTask();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String location = prefs.getString(getString(R.string.pref_location_key), getString(R.string.pref_location_default));
        weatherTask.execute(location,AppId);
    }

    //I have a question here..... why use Void and not void and why using void causes error???
    public class fetchWeatherTask extends AsyncTask<String, Void, String[]> {
        private final String LOG_TAG =  fetchWeatherTask.class.getSimpleName();

        /* The date/time conversion code is going to be moved outside the asynctask later,
         * so for convenience we're breaking it out into its own method now.
         */
        private String getReadableDateString(long time){
            // Because the API returns a unix timestamp (measured in seconds),
            // it must be converted to milliseconds in order to be converted to valid date.
            SimpleDateFormat shortenedDateFormat = new SimpleDateFormat("EEE MMM dd");
            return shortenedDateFormat.format(time);
        }

        /**
         * Prepare the weather high/lows for presentation.
         */
        private String formatHighLows(double high, double low) {
            // For presentation, assume the user doesn't care about tenths of a degree.
            String unitType = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                    .getString(getString(R.string.pref_units_key),
                            getString(R.string.pref_units_metric));

            if (unitType.equals(getString(R.string.pref_units_imperial))){
                high = (high * 1.8) + 32;
                low = (low * 1.8) + 32;
            }else if (!unitType.equals(getString(R.string.pref_units_metric))){
                Log.d(LOG_TAG ,"Unit type not found" + unitType);
            }

            long roundedHigh = Math.round(high);
            long roundedLow = Math.round(low);

            String highLowStr = roundedHigh + "/" + roundedLow;
            return highLowStr;
        }

        /**
         * Take the String representing the complete forecast in JSON Format and
         * pull out the data we need to construct the Strings needed for the wireframes.
         *
         * Fortunately parsing is easy:  constructor takes the JSON string and converts it
         * into an Object hierarchy for us.
         */
        private String[] getWeatherDataFromJson(String forecastJsonStr, int numDays)
                throws JSONException {

            // These are the names of the JSON objects that need to be extracted.
            final String OWM_LIST = "list";
            final String OWM_WEATHER = "weather";
            final String OWM_TEMPERATURE = "temp";
            final String OWM_MAX = "max";
            final String OWM_MIN = "min";
            final String OWM_DESCRIPTION = "main";

            JSONObject forecastJson = new JSONObject(forecastJsonStr);
            JSONArray weatherArray = forecastJson.getJSONArray(OWM_LIST);

            // OWM returns daily forecasts based upon the local time of the city that is being
            // asked for, which means that we need to know the GMT offset to translate this data
            // properly.

            // Since this data is also sent in-order and the first day is always the
            // current day, we're going to take advantage of that to get a nice
            // normalized UTC date for all of our weather.

            Time dayTime = new Time();
            dayTime.setToNow();

            // we start at the day returned by local time. Otherwise this is a mess.
            int julianStartDay = Time.getJulianDay(System.currentTimeMillis(), dayTime.gmtoff);

            // now we work exclusively in UTC
            dayTime = new Time();

            String[] resultStrs = new String[numDays];
            for(int i = 0; i < weatherArray.length(); i++) {
                // For now, using the format "Day, description, hi/low"
                String day;
                String description;
                String highAndLow;

                // Get the JSON object representing the day
                JSONObject dayForecast = weatherArray.getJSONObject(i);

                // The date/time is returned as a long.  We need to convert that
                // into something human-readable, since most people won't read "1400356800" as
                // "this saturday".
                long dateTime;
                // Cheating to convert this to UTC time, which is what we want anyhow
                dateTime = dayTime.setJulianDay(julianStartDay+i);
                day = getReadableDateString(dateTime);

                // description is in a child array called "weather", which is 1 element long.
                JSONObject weatherObject = dayForecast.getJSONArray(OWM_WEATHER).getJSONObject(0);
                description = weatherObject.getString(OWM_DESCRIPTION);

                // Temperatures are in a child object called "temp".  Try not to name variables
                // "temp" when working with temperature.  It confuses everybody.
                JSONObject temperatureObject = dayForecast.getJSONObject(OWM_TEMPERATURE);
                double high = temperatureObject.getDouble(OWM_MAX);
                double low = temperatureObject.getDouble(OWM_MIN);

                highAndLow = formatHighLows(high, low);
                resultStrs[i] = day + " - " + description + " - " + highAndLow;
            }

            for (String s : resultStrs) {
                Log.v(LOG_TAG, "Forecast entry: " + s);
            }
            return resultStrs;

        }

        @Override
        protected void onPostExecute(String[] result) {
            if (result != null){
                mForcastAdapter.clear();
                for (String forecastStr : result){
                    mForcastAdapter.add(forecastStr);
                }
            }
        }


        @Override
        protected String[] doInBackground(String... params) {
            //if no city id
            if (params.length==0){
                return null;
            }
            // These two need to be declared outside the try/catch
            // so that they can be closed in the finally block.
            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;
            // Will contain the raw JSON response as a string.
            String forecastJsonStr = null;

            String format = "json";
            String units = "metric";
            int days = 7;


            try {
                // Construct the URL for the OpenWeatherMap query
                // Possible parameters are available at OWM's forecast API page, at
                // http://openweathermap.org/API#forecast
                final String forcast_url = "http://api.openweathermap.org/data/2.5/forecast/daily?";
                final String url_city = "q";
                final String url_format = "mode";
                final String url_unit = "units";
                final String url_days = "cnt";
                final String url_appid = "appid";
                Uri builtUri = Uri.parse(forcast_url).buildUpon()
                        .appendQueryParameter(url_city, params[0])
                        .appendQueryParameter(url_format, format)
                        .appendQueryParameter(url_unit, units)
                        .appendQueryParameter(url_days, Integer.toString(days))
                        .appendQueryParameter(url_appid, AppId  )
                        .build();

                URL url = new URL(builtUri.toString());
                Log.v(LOG_TAG, "Built URI " + builtUri.toString());
                        // Create the request to OpenWeatherMap, and open the connection
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();

                // Read the input stream into a String
                InputStream inputStream = urlConnection.getInputStream();
                StringBuffer buffer = new StringBuffer();
                if (inputStream == null) {
                    // Nothing to do.
                    return null;
                }
                reader = new BufferedReader(new InputStreamReader(inputStream));

                String line;
                while ((line = reader.readLine()) != null) {
                    // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                    // But it does make debugging a *lot* easier if you print out the completed
                    // buffer for debugging.
                    buffer.append(line + "\n");
                }

                if (buffer.length() == 0) {
                    // Stream was empty.  No point in parsing.
                    return null;
                }
                forecastJsonStr = buffer.toString();
                Log.v(LOG_TAG, "Json string"+forecastJsonStr);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error ", e);
                // If the code didn't successfully get the weather data, there's no point in attempting
                // to parse it.
                return null;
            } finally{
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (final IOException e) {
                        Log.e(LOG_TAG, "Error closing stream", e);
                    }
                }
            }
            try {
                    return getWeatherDataFromJson(forecastJsonStr, days);
                } catch (JSONException e) {
                    Log.e(LOG_TAG, e.getMessage(), e);
                    e.printStackTrace();
                    }
            // This will only happen if there was an error getting or parsing the forecast.
            return null;
        }




    }
}

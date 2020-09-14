package org.tosl.coronawarncompanion.dkdownload;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Pair;
import org.tosl.coronawarncompanion.R;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Path;

public class DKDownloadGermany implements DKDownloadCountry {

    private static final String DK_URL = "https://svc90.main.px.t-online.de/version/v1/diagnosis-keys/country/DE/";

    interface Api {
        @GET("date")
        Maybe<String> listDates();

        @GET("date/{date}/hour")
        Maybe<String> listHours(@Path("date") String date);

        @GET("date/{date}")
        Maybe<ResponseBody> getDKsForDate(@Path("date") String date);

        @GET("date/{date}/hour/{hour}")
        Maybe<ResponseBody> getDKsForDateAndHour(@Path("date") String date, @Path("hour") String hour);
    }

    private static final Api api;

    static {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(DK_URL)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();
        api = retrofit.create(Api.class);
    }

    @SuppressLint("SimpleDateFormat")
    private static final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");

    private static String[] parseCwsListResponse(String str) {
        String reducedStr = str.replace("\"","");
        reducedStr = reducedStr.replace("[","");
        reducedStr = reducedStr.replace("]","");
        return reducedStr.split(",");
    }

    private static String getStringFromDate(Date date) {
        StringBuffer stringBuffer = new StringBuffer();
        return dateFormatter.format(date, stringBuffer, new FieldPosition(0)).toString();
    }

    public Observable<Pair<byte[], String>> getDKBytes(Context context, Date minDate) {

        return api.listDates()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .onErrorComplete()
                .map(datesListString -> Arrays.asList(parseCwsListResponse(datesListString)))
                .flatMapObservable(datesList -> {
                    Observable<ResponseBody> responsesForDays = Observable.fromIterable(datesList)
                            .map(dateFormatter::parse)
                            .filter(date -> date.compareTo(minDate) > 0)
                            .flatMapMaybe(date -> api.getDKsForDate(getStringFromDate(date))
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .onErrorComplete()
                            );
                    Date lastDate = dateFormatter.parse(datesList.get(datesList.size()-1));
                    Calendar c = Calendar.getInstance();
                    if (lastDate == null) {
                        return responsesForDays;
                    }
                    c.setTime(lastDate);
                    c.add(Calendar.DATE, 1);
                    Date currentDate = c.getTime();
                    String currentDateString = getStringFromDate(currentDate);
                    Observable<ResponseBody> responseForHours = api.listHours(currentDateString)
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .onErrorComplete()
                            .flatMapObservable(hoursListString -> Observable
                                    .fromIterable(Arrays.asList(parseCwsListResponse(hoursListString))))
                            .flatMapMaybe(hour -> api.getDKsForDateAndHour(currentDateString, hour)
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .onErrorComplete()
                            );
                    return responsesForDays.concatWith(responseForHours);
                })
                .map(responseBody -> new Pair<>(responseBody.bytes(), getCountryCode(context)));
    }

    @Override
    public String getCountryCode(Context context) {
        return context.getResources().getString(R.string.country_code_germany);
    }
}

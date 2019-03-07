package com.hoc.weatherapp.ui.cities

import android.annotation.SuppressLint
import android.app.Application
import androidx.recyclerview.widget.RecyclerView
import com.hannesdorfmann.mosby3.mvi.MviBasePresenter
import com.hoc.weatherapp.data.CityRepository
import com.hoc.weatherapp.data.CurrentWeatherRepository
import com.hoc.weatherapp.data.FiveDayForecastRepository
import com.hoc.weatherapp.data.local.SettingPreferences
import com.hoc.weatherapp.data.models.entity.City
import com.hoc.weatherapp.data.models.entity.CityAndCurrentWeather
import com.hoc.weatherapp.ui.cities.CitiesContract.*
import com.hoc.weatherapp.ui.cities.CitiesContract.PartialStateChange.*
import com.hoc.weatherapp.ui.cities.CitiesContract.SearchStringIntent.InitialSearchStringIntent
import com.hoc.weatherapp.utils.*
import com.hoc.weatherapp.worker.WorkerUtil
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function
import io.reactivex.rxkotlin.*
import io.reactivex.rxkotlin.Observables.combineLatest
import java.util.concurrent.TimeUnit

class CitiesPresenter(
  private val cityRepository: CityRepository,
  private val currentWeatherRepository: CurrentWeatherRepository,
  private val fiveDayForecastRepository: FiveDayForecastRepository,
  private val settingPreferences: SettingPreferences,
  private val androidApplication: Application
) : MviBasePresenter<View, ViewState>() {

  private val searchIntentProcessor =
    ObservableTransformer<SearchStringIntent, List<CityAndCurrentWeather>> {
      it.publish { shared ->
        Observable.mergeArray(
          shared.ofType<InitialSearchStringIntent>().take(1),
          shared.notOfType<InitialSearchStringIntent>()
        )
      }
        .cast<CitiesContract.SearchStringIntent>()
        .map { it.value }
        .switchMap(currentWeatherRepository::getAllCityAndCurrentWeathers)
        .share()
    }

  private val cityListItemsPartialChange =
    ObservableTransformer<List<CityAndCurrentWeather>, PartialStateChange> {
      combineLatest(
        cityRepository.getSelectedCity(),
        it,
        settingPreferences.temperatureUnitPreference.observable
      )
        .map { (city, list, temperatureUnit) ->
          list.map {
            CityListItem(
              city = it.city,
              temperatureMin = temperatureUnit.format(it.currentWeather.temperatureMin),
              temperatureMax = temperatureUnit.format(it.currentWeather.temperatureMax),
              weatherDescription = it.currentWeather.description,
              weatherConditionId = it.currentWeather.weatherConditionId,
              weatherIcon = it.currentWeather.icon,
              isSelected = it.city == city.getOrNull(),
              lastUpdated = it.currentWeather.dataTime.toZonedDateTime(it.city.zoneId)
            )
          }
        }
        .map(::CityListItems)
        .cast<PartialStateChange>()
        .observeOn(AndroidSchedulers.mainThread())
        .onErrorResumeNext(showError)
    }

  private val changeSelectedCityProcessor: ObservableTransformer<City, Any> =
    ObservableTransformer {
      val getWeatherSingle = currentWeatherRepository
        .refreshCurrentWeatherOfSelectedCity()
        .zipWith(fiveDayForecastRepository.refreshFiveDayForecastOfSelectedCity())
        .doOnSuccess { (cityAndCurrentWeather) ->
          if (settingPreferences.autoUpdatePreference.value) {
            WorkerUtil.enqueueUpdateCurrentWeatherWorkRequest()
            WorkerUtil.enqueueUpdateDailyWeatherWorkRequest()
          }
          androidApplication.showNotificationIfEnabled(cityAndCurrentWeather, settingPreferences)
        }
      it.switchMap { city ->
        cityRepository
          .changeSelectedCity(city)
          .andThen(getWeatherSingle)
          .toObservable()
          .onErrorResumeNext(Observable.empty())
      }
    }

  @SuppressLint("CheckResult")
  override fun bindIntents() {

    intent(View::changeSelectedCity)
      .compose(changeSelectedCityProcessor)
      .subscribeBy(
        onNext = { debug("setupChangeSelectedCity onNext=$it", TAG) },
        onError = { debug("setupChangeSelectedCity onNext=$it", TAG) }
      )

    val cityAndCurrentWeathers = intent(View::searchStringIntent).compose(searchIntentProcessor)

    subscribeViewState(
      Observable.mergeArray(
        cityAndCurrentWeathers.compose(cityListItemsPartialChange),
        deleteCityPartialChange(cityAndCurrentWeathers),
        refreshWeather(cityAndCurrentWeathers)
      ).scan(ViewState(), reducer)
        .distinctUntilChanged()
        .doOnNext { debug("CitiesPresenter ViewState = $it", TAG) }
        .observeOn(AndroidSchedulers.mainThread()),
      View::render
    )
  }

  private fun refreshWeather(cityAndCurrentWeathers: Observable<List<CityAndCurrentWeather>>): Observable<PartialStateChange> {
    return intent(View::refreshCurrentWeatherAtPosition)
      .filter { it != RecyclerView.NO_POSITION }
      .withLatestFrom(cityAndCurrentWeathers)
      .map { (position, list) -> list[position].city }
      .flatMap { city ->
        currentWeatherRepository
          .refreshWeatherOf(city)
          .doOnSuccess { (cityAndCurrentWeather) ->
            /**
             * If refresh current selected city
             */
            if (cityRepository.selectedCity == city) {

              if (settingPreferences.autoUpdatePreference.value) {
                WorkerUtil.enqueueUpdateCurrentWeatherWorkRequest()
                WorkerUtil.enqueueUpdateDailyWeatherWorkRequest()
              }

              androidApplication.showNotificationIfEnabled(
                cityAndCurrentWeather,
                settingPreferences
              )
            }
          }
          .map { it.first.city }
          .toObservable()
          .observeOn(AndroidSchedulers.mainThread())
          .flatMap { updatedCity ->
            Observable
              .timer(SNACKBAR_DURATION, TimeUnit.MILLISECONDS)
              .map<PartialStateChange> {
                RefreshWeather(
                  showMessage = false,
                  refreshCity = updatedCity
                )
              }
              .startWith(RefreshWeather(showMessage = true, refreshCity = updatedCity))
          }
          .onErrorResumeNext(showError)
      }
  }

  private fun deleteCityPartialChange(cityAndCurrentWeathers: Observable<List<CityAndCurrentWeather>>): Observable<PartialStateChange> {
    return intent(View::deleteCityAtPosition)
      .filter { it != RecyclerView.NO_POSITION }
      .withLatestFrom(cityAndCurrentWeathers)
      .map { (position, list) -> list[position].city }
      .flatMap { city ->
        cityRepository
          .deleteCity(city)
          .doOnSuccess {
            /**
             * If delete selected city
             */
            if (cityRepository.selectedCity === null) {
              androidApplication.cancelNotificationById(WEATHER_NOTIFICATION_ID)
              WorkerUtil.cancelUpdateCurrentWeatherWorkRequest()
              WorkerUtil.cancelUpdateDailyWeatherWorkWorkRequest()
            }
          }
          .toObservable()
          .observeOn(AndroidSchedulers.mainThread())
          .flatMap { deletedCity ->
            Observable
              .timer(SNACKBAR_DURATION, TimeUnit.MILLISECONDS)
              .map<PartialStateChange> {
                DeleteCity(
                  showMessage = false,
                  deletedCity = deletedCity
                )
              }
              .startWith(DeleteCity(showMessage = true, deletedCity = deletedCity))
          }
          .onErrorResumeNext(showError)
      }
  }

  private companion object {
    private const val TAG = "__cities__"

    private const val SNACKBAR_DURATION = 2_000L

    @JvmStatic
    private val reducer =
      BiFunction<ViewState, PartialStateChange, ViewState> { viewState, partialStateChange ->
        when (partialStateChange) {
          is CityListItems -> viewState.copy(
            cityListItems = partialStateChange.items,
            error = null
          )
          is Error -> viewState.copy(
            showError = partialStateChange.showMessage,
            error = partialStateChange.throwable
          )
          is DeleteCity -> viewState.copy(
            showDeleteCitySuccessfully = partialStateChange.showMessage,
            deletedCity = partialStateChange.deletedCity
          )
          is RefreshWeather -> viewState.copy(
            showRefreshSuccessfully = partialStateChange.showMessage,
            refreshCity = partialStateChange.refreshCity
          )
        }
      }

    @JvmStatic
    private val showError = Function<Throwable, Observable<PartialStateChange>> { throwable ->
      Observable
        .timer(SNACKBAR_DURATION, TimeUnit.MILLISECONDS)
        .map<PartialStateChange> { Error(showMessage = false, throwable = throwable) }
        .startWith(Error(showMessage = true, throwable = throwable))
    }
  }
}


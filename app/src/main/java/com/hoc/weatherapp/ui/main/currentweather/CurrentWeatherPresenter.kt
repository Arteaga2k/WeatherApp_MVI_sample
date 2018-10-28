package com.hoc.weatherapp.ui.main.currentweather

import com.hannesdorfmann.mosby3.mvi.MviBasePresenter
import com.hoc.weatherapp.data.NoSelectedCityException
import com.hoc.weatherapp.data.Repository
import com.hoc.weatherapp.data.models.entity.CityAndCurrentWeather
import com.hoc.weatherapp.ui.main.currentweather.CurrentWeatherContract.InitialRefreshIntent
import com.hoc.weatherapp.ui.main.currentweather.CurrentWeatherContract.PartialStateChange
import com.hoc.weatherapp.ui.main.currentweather.CurrentWeatherContract.View
import com.hoc.weatherapp.ui.main.currentweather.CurrentWeatherContract.ViewState
import com.hoc.weatherapp.utils.None
import com.hoc.weatherapp.utils.Some
import com.hoc.weatherapp.utils.debug
import com.hoc.weatherapp.utils.notOfType
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.cast
import io.reactivex.rxkotlin.ofType
import java.util.concurrent.TimeUnit

private const val TAG = "currrentweather"

class CurrentWeatherPresenter(private val repository: Repository) :
  MviBasePresenter<View, ViewState>() {
  override fun bindIntents() {

    val refresh = intent(View::refreshCurrentWeatherIntent)
      .publish { shared ->
        Observable.mergeArray(
          shared.ofType<InitialRefreshIntent>().take(1),
          shared.notOfType<InitialRefreshIntent>()
        )
      }
      .doOnNext { debug("refresh intent $it") }
      .switchMap {
        repository.refreshCurrentWeather()
          .toObservable()
          .switchMap(::weather)
          .onErrorResumeNext(::showError)
      }
      .doOnNext { debug("refreshWeather $it", TAG) }

    val cityAndWeather = repository.getSelectedCityAndCurrentWeather()
      .publish { shared ->
        Observable.mergeArray(
          shared.ofType<None>().switchMap { showError(NoSelectedCityException) },
          shared.ofType<Some<CityAndCurrentWeather>>()
            .map { it.value }
            .map { it.currentWeather }
            .map { PartialStateChange.Weather(weather = it) }
            .cast<PartialStateChange>()
            .onErrorResumeNext(::showError)
        )
      }
      .doOnNext { debug("cityAndWeather $it", TAG) }

    subscribeViewState(
      Observable.mergeArray(refresh, cityAndWeather)
        .scan(ViewState(), ::reduce)
        .distinctUntilChanged()
        .doOnNext {
          debug(
            "CurrentWeatherPresenter ViewState = $it",
            TAG
          )
        }
        .observeOn(AndroidSchedulers.mainThread()),
      View::render
    )
  }

  private fun reduce(viewState: ViewState, partialStateChange: PartialStateChange): ViewState {
    return when (partialStateChange) {
      is PartialStateChange.Error -> viewState.copy(
        showError = partialStateChange.showMessage,
        error = partialStateChange.throwable,
        weather = if (partialStateChange.throwable is NoSelectedCityException) {
          null
        } else {
          viewState.weather
        }
      )
      is PartialStateChange.Weather -> viewState.copy(
        weather = partialStateChange.weather,
        error = null
      )
      is PartialStateChange.RefreshWeatherSuccess -> viewState.copy(
        weather = partialStateChange.weather,
        showRefreshSuccessfully = partialStateChange.showMessage,
        error = null
      )
    }
  }

  private fun showError(throwable: Throwable): Observable<PartialStateChange> {
    return Observable.timer(2_000, TimeUnit.MILLISECONDS)
      .map {
        PartialStateChange.Error(throwable = throwable, showMessage = false)
      }
      .startWith(
        PartialStateChange.Error(throwable = throwable, showMessage = true)
      )
      .cast()
  }

  private fun weather(cityAndCurrentWeather: CityAndCurrentWeather): Observable<PartialStateChange> {
    return Observable.timer(2_000, TimeUnit.MILLISECONDS)
      .map {
        PartialStateChange.RefreshWeatherSuccess(
          showMessage = false,
          weather = cityAndCurrentWeather.currentWeather
        )
      }
      .startWith(
        PartialStateChange.RefreshWeatherSuccess(
          showMessage = true,
          weather = cityAndCurrentWeather.currentWeather
        )
      )
      .cast()
  }
}
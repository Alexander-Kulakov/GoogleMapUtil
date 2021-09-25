package com.example.googlemaps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Address
import android.os.Looper
import android.util.Log
import androidx.annotation.ColorRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.example.googlemaps.mappers.toModel
import com.example.googlemaps.models.DirectionSegment
import com.example.googlemaps.utils.MapUtils
import com.example.googlemaps.utils.Utils
import com.example.googlemaputil_core.common.DIRECTION_MARKER
import com.example.googlemaputil_core.common.DIRECTION_TYPE
import com.example.googlemaputil_core.common.MAP_MODE
import com.example.googlemaputil_core.common.Result
import com.example.googlemaputil_core.models.directions.Direction
import com.example.googlemaputil_core.models.place_info.PlaceInfo
import com.example.googlemaputil_core.use_cases.GetDirectionUseCase
import com.example.googlemaputil_core.use_cases.GetInfoByLocationUseCase
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.maps.android.PolyUtil
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject

open class GoogleMapVM(
    private val app: Application,
    private val getInfoByLocationUseCase: GetInfoByLocationUseCase,
    private val getDirectionUseCase: GetDirectionUseCase,
): AndroidViewModel(app) {

    companion object {
        private const val TAG = "GoogleMapViewModel"

        private const val DEFAULT_LOCATION_INTERVAL = 5000L
        private const val DEFAULT_FASTEST_LOCATION_INTERVAL = 3000L

        private const val DEFAULT_POLYLINE_WIDTH = 8f

        private val colors = listOf(
            R.color.black,
            R.color.green,
            R.color.red,
            R.color.teal_700,
            R.color.purple_500,
            R.color.orange
        )
    }

    var currentAddress = BehaviorSubject.create<Address>()
    var currentLocation = BehaviorSubject.create<LatLng>()

    val currentLanguage: String?
        get() = currentAddress.value?.locale?.language


    private val compositeDisposable = CompositeDisposable()
    private val placesClient = Places.createClient(app)
    private val fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(app)


    val placeMarker = BehaviorSubject.create<MarkerOptions>()
    val originMarker = BehaviorSubject.create<MarkerOptions>(
        /*MarkerOptions()
            .icon(Utils.getBitmapFromVector(app, R.drawable.ic_origin_marker))*/
    )
    val destinationMarker = BehaviorSubject.create<MarkerOptions>(
        /*MarkerOptions()
            .icon(Utils.getBitmapFromVector(app, R.drawable.ic_destination_marker))*/
    )

    val currentCameraPosition = BehaviorSubject.create<LatLng>()

    val infoWindowAdapter = BehaviorSubject.create<GoogleMap.InfoWindowAdapter>()

    val currentMapMode = BehaviorSubject.createDefault(MAP_MODE.PLACE)
    var currentDirectionMarkerType = BehaviorSubject.createDefault(DIRECTION_MARKER.DESTINATION)


    val placeInfo = BehaviorSubject.create<Result<PlaceInfo>>()
    val direction = BehaviorSubject.create<Result<Direction>>()

    val directionSegments = BehaviorSubject.createDefault<List<DirectionSegment>>(emptyList())

    val basePlaceMarker: MarkerOptions
        get() = MarkerOptions()

    val baseOriginMarker: MarkerOptions
        get() = MarkerOptions().icon(Utils.getBitmapFromVector(app, R.drawable.ic_origin_marker))

    val baseDestinationMarker: MarkerOptions
        get() = MarkerOptions().icon(Utils.getBitmapFromVector(app, R.drawable.ic_destination_marker))


    fun setMarker(placeId: String, latLng: LatLng) {
        when(currentMapMode.value) {
            MAP_MODE.PLACE -> {
                if(placeMarker.value != null)
                    placeMarker.onNext(placeMarker.value!!.position(latLng))
                else
                    placeMarker.onNext(basePlaceMarker.position(latLng))
                getInfoByLocation(placeId)
            }
            MAP_MODE.DIRECTION -> {
                if(currentDirectionMarkerType.value == DIRECTION_MARKER.ORIGIN) {
                    originMarker.onNext(
                        if(originMarker.value != null)
                            originMarker.value!!.position(latLng)
                        else
                            baseOriginMarker.position(latLng)
                    )
                } else {
                    destinationMarker.onNext(
                        if(originMarker.value != null)
                            destinationMarker.value!!.position(latLng)
                        else
                            baseDestinationMarker.position(latLng)
                    )
                }
            }
        }
        currentCameraPosition.onNext(latLng)
    }


    init {
        compositeDisposable.addAll(
            currentMapMode.subscribe {
                val isPlaceMode = it == MAP_MODE.PLACE

                placeMarker.value?.let {
                    placeMarker.onNext(it.visible(isPlaceMode))
                }
                originMarker.value?.let {
                    originMarker.onNext(it.visible(isPlaceMode))
                }
                destinationMarker.value?.let {
                    destinationMarker.onNext(it.visible(isPlaceMode))
                }
            },
            currentLocation.subscribe {
                val address = MapUtils.getAddressByLocation(app, it) ?: return@subscribe
                currentAddress.onNext(address)
            }
        )
        observeDeviceLocation()
    }

    fun getDirection(dirType: DIRECTION_TYPE) {
        if(originMarker.value == null || destinationMarker.value == null) return

        val origin = originMarker.value!!.position.toModel()
        val destination = destinationMarker.value!!.position.toModel()

        direction.onNext(Result.Loading())
        compositeDisposable.add(
            getDirectionUseCase.invoke(origin, destination, dirType, currentLanguage)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    direction.onNext(Result.Success(it))
                    buildDirection(it)
                }, {
                    direction.onNext(Result.Failure(it))
                })
        )
    }

    private fun buildDirection(direction: Direction) {
        val newDirectionSegments = mutableListOf<DirectionSegment>()

        for (route in direction.routes!!) {
            for(leg in route.legs) {
                for((currentColorIndex, step) in leg.steps.orEmpty().withIndex()) {
                    val polylineList = mutableListOf<LatLng>()
                    polylineList.addAll(PolyUtil.decode(step.polyline.points))

                    val polylineOptions = createPolylineOptions(polylineList, colors[currentColorIndex % colors.size])

                    val midPoint = polylineOptions.points[polylineOptions.points.size / 2]

                    val invisibleMarker =
                        BitmapDescriptorFactory.fromBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

                    val markerOptions = MarkerOptions().position(midPoint).icon(invisibleMarker)

                    newDirectionSegments.add(
                        DirectionSegment(step, polylineOptions, markerOptions)
                    )
                }
            }
        }

        directionSegments.onNext(newDirectionSegments)
    }


    fun getInfoByLocation(placeId: String) {
        Log.w(TAG, "current locality $currentLanguage")

        placeInfo.onNext(Result.Loading())

        compositeDisposable.add(
            getInfoByLocationUseCase.invoke(placeId, currentLanguage).observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    placeInfo.onNext(Result.Success(it))
                }, {
                    placeInfo.onNext(Result.Failure(it))
                })
        )
    }

    @SuppressLint("MissingPermission")
    fun observeDeviceLocation() {
        val locationRequest = LocationRequest()
            .setInterval(DEFAULT_LOCATION_INTERVAL)
            .setFastestInterval(DEFAULT_FASTEST_LOCATION_INTERVAL)
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)

        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).setAlwaysShow(true)

        val locationSettingsResponseTask = LocationServices.getSettingsClient(app).checkLocationSettings(builder.build())

        locationSettingsResponseTask.addOnCompleteListener {
            try {
                if (isCoarseAndFineLocationPermissionsGranted()) {

                    fusedLocationProviderClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            super.onLocationResult(locationResult)
                            val lat = locationResult.lastLocation.latitude
                            val lng = locationResult.lastLocation.longitude

                            val newLocation = LatLng(lat, lng)
                            if(currentLocation.value == null) {
                                currentLocation.onNext(newLocation)
                                currentCameraPosition.onNext(newLocation)
                            }

                            currentLocation.onNext(newLocation)
                        }
                    }, Looper.getMainLooper())

                }

            } catch (e: ApiException) {
                Log.e(TAG, "getDeviceLocation: SecurityException: " + e.message)
            }
        }
    }

    fun isCoarseAndFineLocationPermissionsGranted(): Boolean {
        return ActivityCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleMapMode() {
        currentMapMode.onNext(
            if(currentMapMode.value == MAP_MODE.PLACE)
                MAP_MODE.DIRECTION
            else
                MAP_MODE.PLACE
        )
    }

    private fun createPolylineOptions(polylineList: List<LatLng>, @ColorRes color: Int): PolylineOptions {
        return PolylineOptions()
            .color(ContextCompat.getColor(app, color))
            .width(DEFAULT_POLYLINE_WIDTH)
            .startCap(ButtCap())
            .jointType(JointType.ROUND)
            .clickable(true)
            .addAll(polylineList)
    }

    override fun onCleared() {
        super.onCleared()
        compositeDisposable.clear()
    }
}
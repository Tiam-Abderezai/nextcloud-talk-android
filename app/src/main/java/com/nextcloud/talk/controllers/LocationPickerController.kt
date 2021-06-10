/*
 * Nextcloud Talk application
 *
 * @author Marcel Hibbe
 * Copyright (C) 2021 Marcel Hibbe <dev@mhibbe.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.controllers

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.PermissionChecker
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.MenuItemCompat
import androidx.preference.PreferenceManager
import autodagger.AutoInjector
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler
import com.nextcloud.talk.R
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.controllers.base.NewBaseController
import com.nextcloud.talk.controllers.util.viewBinding
import com.nextcloud.talk.databinding.ControllerLocationBinding
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.DisplayUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.database.user.UserUtils
import fr.dudie.nominatim.client.JsonNominatimClient
import fr.dudie.nominatim.model.Address
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.http.client.HttpClient
import org.apache.http.conn.ClientConnectionManager
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.scheme.SchemeRegistry
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.SingleClientConnManager
import org.osmdroid.config.Configuration.getInstance
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class LocationPickerController(args: Bundle) :
    NewBaseController(
        R.layout.controller_location,
        args
    ),
    SearchView.OnQueryTextListener,
    LocationListener,
    GeocodingController.GeocodingResultListener {
    private val binding: ControllerLocationBinding by viewBinding(ControllerLocationBinding::bind)

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var userUtils: UserUtils

    var nominatimClient: JsonNominatimClient? = null

    var roomToken: String?

    var myLocation: GeoPoint = GeoPoint(0.0, 0.0)
    private var locationManager: LocationManager? = null
    private lateinit var locationOverlay: MyLocationNewOverlay

    var moveToCurrentLocationWasClicked: Boolean = true
    var readyToShareLocation: Boolean = false
    var searchItem: MenuItem? = null
    var searchView: SearchView? = null

    var receivedChosenGeocodingResult: Boolean = false
    var geocodedLat: Double = 0.0
    var geocodedLon: Double = 0.0
    var geocodedName: String = ""

    init {
        setHasOptionsMenu(true)
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
        getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))

        roomToken = args.getString(KEY_ROOM_TOKEN)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        initMap()
    }

    @Suppress("Detekt.TooGenericExceptionCaught")
    override fun onDetach(view: View) {
        super.onDetach(view)
        try {
            locationManager!!.removeUpdates(this)
        } catch (e: Exception) {
            Log.e(TAG, "error when trying to remove updates for location Manager", e)
        }
        locationOverlay.disableMyLocation()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_locationpicker, menu)
        searchItem = menu.findItem(R.id.location_action_search)
        initSearchView()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        actionBar?.title = context!!.getString(R.string.nc_share_location)
    }

    override val title: String
        get() =
            resources!!.getString(R.string.nc_share_location)

    override fun onViewBound(view: View) {
        setLocationDescription(false, receivedChosenGeocodingResult)
        binding.shareLocation.isClickable = false
        binding.shareLocation.setOnClickListener {
            if (readyToShareLocation) {
                shareLocation(
                    binding.map.mapCenter?.latitude,
                    binding.map.mapCenter?.longitude,
                    binding.placeName.text.toString()
                )
            } else {
                Log.w(TAG, "readyToShareLocation was false while user tried to share location.")
            }
        }
    }

    private fun initSearchView() {
        if (activity != null) {
            val searchManager = activity!!.getSystemService(Context.SEARCH_SERVICE) as SearchManager
            if (searchItem != null) {
                searchView = MenuItemCompat.getActionView(searchItem) as SearchView
                searchView?.maxWidth = Int.MAX_VALUE
                searchView?.inputType = InputType.TYPE_TEXT_VARIATION_FILTER
                var imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appPreferences!!.isKeyboardIncognito) {
                    imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                searchView?.imeOptions = imeOptions
                searchView?.queryHint = resources!!.getString(R.string.nc_search)
                searchView?.setSearchableInfo(searchManager.getSearchableInfo(activity!!.componentName))
                searchView?.setOnQueryTextListener(this)
            }
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        if (!query.isNullOrEmpty()) {
            val bundle = Bundle()
            bundle.putString(BundleKeys.KEY_GEOCODING_QUERY, query)
            bundle.putString(BundleKeys.KEY_ROOM_TOKEN, roomToken)
            router.pushController(
                RouterTransaction.with(GeocodingController(bundle, this))
                    .pushChangeHandler(HorizontalChangeHandler())
                    .popChangeHandler(HorizontalChangeHandler())
            )
        }
        return true
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return true
    }

    private fun initMap() {
        if (!isFineLocationPermissionGranted()) {
            requestFineLocationPermission()
        }

        binding.map.setTileSource(TileSourceFactory.MAPNIK)

        binding.map.onResume()

        locationManager = activity!!.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager!!.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, this)
        } catch (ex: SecurityException) {
            Log.w(TAG, "Error requesting location updates", ex)
        }

        val copyrightOverlay = CopyrightOverlay(context)
        binding.map.overlays?.add(copyrightOverlay)

        binding.map.setMultiTouchControls(true)
        binding.map.isTilesScaledToDpi = true

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), binding.map)
        locationOverlay.enableMyLocation()
        locationOverlay.setPersonHotspot(PERSON_HOT_SPOT_X, PERSON_HOT_SPOT_Y)
        locationOverlay.setPersonIcon(
            DisplayUtils.getBitmap(
                ResourcesCompat.getDrawable(resources!!, R.drawable.current_location_circle, null)
            )
        )
        binding.map.overlays?.add(locationOverlay)

        val mapController = binding.map.controller

        if (receivedChosenGeocodingResult) {
            mapController?.setZoom(ZOOM_LEVEL_RECEIVED_RESULT)
        } else {
            mapController?.setZoom(ZOOM_LEVEL_DEFAULT)
        }

        val zoomToCurrentPositionOnFirstFix = !receivedChosenGeocodingResult
        locationOverlay.runOnFirstFix {
            myLocation = locationOverlay.myLocation
            if (zoomToCurrentPositionOnFirstFix) {
                activity!!.runOnUiThread {
                    mapController?.setZoom(ZOOM_LEVEL_DEFAULT)
                    mapController?.setCenter(myLocation)
                }
            }
        }

        if (receivedChosenGeocodingResult && geocodedLat != GEOCODE_ZERO && geocodedLon != GEOCODE_ZERO) {
            mapController?.setCenter(GeoPoint(geocodedLat, geocodedLon))
        }

        binding.centerMapButton.setOnClickListener {
            mapController?.animateTo(myLocation)
            moveToCurrentLocationWasClicked = true
        }

        binding.map.addMapListener(
            DelayedMapListener(
                object : MapListener {
                    override fun onScroll(paramScrollEvent: ScrollEvent): Boolean {
                        when {
                            moveToCurrentLocationWasClicked -> {
                                setLocationDescription(isGpsLocation = true, isGeocodedResult = false)
                                moveToCurrentLocationWasClicked = false
                            }
                            receivedChosenGeocodingResult -> {
                                binding.shareLocation.isClickable = true
                                setLocationDescription(isGpsLocation = false, isGeocodedResult = true)
                                receivedChosenGeocodingResult = false
                            }
                            else -> {
                                binding.shareLocation.isClickable = true
                                setLocationDescription(isGpsLocation = false, isGeocodedResult = false)
                            }
                        }
                        readyToShareLocation = true
                        return true
                    }

                    override fun onZoom(event: ZoomEvent): Boolean {
                        return false
                    }
                })
        )
    }

    private fun setLocationDescription(isGpsLocation: Boolean, isGeocodedResult: Boolean) {
        when {
            isGpsLocation -> {
                binding.shareLocationDescription.text = context!!.getText(R.string.nc_share_current_location)
                binding.placeName.visibility = View.GONE
                binding.placeName.text = ""
            }
            isGeocodedResult -> {
                binding.shareLocationDescription.text = context!!.getText(R.string.nc_share_this_location)
                binding.placeName.visibility = View.VISIBLE
                binding.placeName.text = geocodedName
            }
            else -> {
                binding.shareLocationDescription.text = context!!.getText(R.string.nc_share_this_location)
                binding.placeName.visibility = View.GONE
                binding.placeName.text = ""
            }
        }
    }

    private fun shareLocation(selectedLat: Double?, selectedLon: Double?, locationName: String?) {
        if (selectedLat != null || selectedLon != null) {

            val name = locationName
            if (name.isNullOrEmpty()) {
                initGeocoder()
                searchPlaceNameForCoordinates(selectedLat!!, selectedLon!!)
            } else {
                executeShareLocation(selectedLat, selectedLon, locationName)
            }
        }
    }

    private fun executeShareLocation(selectedLat: Double?, selectedLon: Double?, locationName: String?) {
        val objectId = "geo:$selectedLat,$selectedLon"
        val metaData: String =
            "{\"type\":\"geo-location\",\"id\":\"geo:$selectedLat,$selectedLon\",\"latitude\":\"$selectedLat\"," +
                "\"longitude\":\"$selectedLon\",\"name\":\"$locationName\"}"

        ncApi.sendLocation(
            ApiUtils.getCredentials(userUtils.currentUser?.username, userUtils.currentUser?.token),
            ApiUtils.getUrlToSendLocation(userUtils.currentUser?.baseUrl, roomToken),
            "geo-location",
            objectId,
            metaData
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<GenericOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(t: GenericOverall) {
                    router.popCurrentController()
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "error when trying to share location", e)
                    Toast.makeText(context, R.string.nc_common_error_sorry, Toast.LENGTH_LONG).show()
                    router.popCurrentController()
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    private fun isFineLocationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (PermissionChecker.checkSelfPermission(
                    context!!,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PermissionChecker.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Permission is granted")
                return true
            } else {
                Log.d(TAG, "Permission is revoked")
                return false
            }
        } else {
            Log.d(TAG, "Permission is granted")
            return true
        }
    }

    private fun requestFineLocationPermission() {
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            REQUEST_PERMISSIONS_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE &&
            grantResults.size > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initMap()
        } else {
            Toast.makeText(context, context!!.getString(R.string.nc_location_permission_required), Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun receiveChosenGeocodingResult(lat: Double, lon: Double, name: String) {
        receivedChosenGeocodingResult = true
        geocodedLat = lat
        geocodedLon = lon
        geocodedName = name
    }

    private fun initGeocoder() {
        val registry = SchemeRegistry()
        registry.register(Scheme("https", SSLSocketFactory.getSocketFactory(), HTTPS_PORT))
        val connexionManager: ClientConnectionManager = SingleClientConnManager(null, registry)
        val httpClient: HttpClient = DefaultHttpClient(connexionManager, null)
        val baseUrl = context!!.getString(R.string.osm_geocoder_url)
        val email = context!!.getString(R.string.osm_geocoder_contact)
        nominatimClient = JsonNominatimClient(baseUrl, httpClient, email)
    }

    private fun searchPlaceNameForCoordinates(lat: Double, lon: Double): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            executeGeocodingRequest(lat, lon)
        }
        return true
    }

    @Suppress("Detekt.TooGenericExceptionCaught")
    private suspend fun executeGeocodingRequest(lat: Double, lon: Double) {
        var address: Address? = null
        try {
            address = nominatimClient!!.getAddress(lon, lat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get geocoded addresses", e)
            Toast.makeText(context, R.string.nc_common_error_sorry, Toast.LENGTH_LONG).show()
        }
        updateResultOnMainThread(lat, lon, address?.displayName)
    }

    private suspend fun updateResultOnMainThread(lat: Double, lon: Double, addressName: String?) {
        withContext(Dispatchers.Main) {
            executeShareLocation(lat, lon, addressName)
        }
    }

    override fun onLocationChanged(location: Location?) {
        myLocation = GeoPoint(location)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // empty
    }

    override fun onProviderEnabled(provider: String?) {
        // empty
    }

    override fun onProviderDisabled(provider: String?) {
        // empty
    }

    companion object {
        private const val TAG = "LocPicker"
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 1
        private const val PERSON_HOT_SPOT_X: Float = 20.0F
        private const val PERSON_HOT_SPOT_Y: Float = 20.0F
        private const val ZOOM_LEVEL_RECEIVED_RESULT: Double = 14.0
        private const val ZOOM_LEVEL_DEFAULT: Double = 14.0
        private const val GEOCODE_ZERO: Double = 0.0
        private const val HTTPS_PORT: Int = 443
    }
}

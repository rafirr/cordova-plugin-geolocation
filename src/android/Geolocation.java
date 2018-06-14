/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at
         http://www.apache.org/licenses/LICENSE-2.0
       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
 */


package org.apache.cordova.geolocation;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.Manifest;
import android.os.Build;
import android.os.Looper;

import by.chemerisuk.cordova.support.CordovaMethod;
import by.chemerisuk.cordova.support.ReflectiveCordovaPlugin;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.apache.cordova.LOG;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.callback.Callback;

public class Geolocation extends ReflectiveCordovaPlugin implements OnCompleteListener<Location> {
    public final static String TAG = "GeolocationPluginFixed";
    public final static int PERMISSION_DENIED = 1;
    public final static int POSITION_UNAVAILABLE = 2;
    public final static int REQUEST_LOCATION_ACCURACY_CODE = 235524;
    public final static String [] permissions = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION };

    private LocationManager locationManager;
    private FusedLocationProviderClient locationsClient;
    private SettingsClient settingsClient;

    private Map<String, LocationCallback> watchers = new HashMap<String, LocationCallback>();
    private Map<String, LocationRequest> requests = new HashMap<String, LocationRequest>();
    private List<CallbackContext> locationCallbacks = new ArrayList<CallbackContext>();

    @Override
    protected void pluginInitialize() {
		
		LOG.d(TAG, "onPluginInitialize");
		
        if (hasLocationPermission()) {
			
			LOG.d(TAG, "hasLocationPermission");
			
            initLocationClient();
        } else {
			
			LOG.d(TAG, "noPermissions");
			
            PermissionHelper.requestPermissions(this, 0, permissions);
        }
    }

    private void initLocationClient() {
		
		LOG.d(TAG, "initLocationClient");
			
			
        this.locationManager = (LocationManager) cordova.getActivity().getSystemService(Context.LOCATION_SERVICE);
        this.locationsClient = LocationServices.getFusedLocationProviderClient(cordova.getActivity());
        this.settingsClient = LocationServices.getSettingsClient(cordova.getActivity());

        LocationSettingsRequest.Builder settingsBuilder = new LocationSettingsRequest.Builder();
        settingsBuilder.addLocationRequest(new LocationRequest().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY));
        settingsBuilder.addLocationRequest(new LocationRequest().setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY));
        settingsBuilder.setAlwaysShow(true);

        this.settingsClient
            .checkLocationSettings(settingsBuilder.build())
            .addOnCompleteListener(new OnCompleteListener<LocationSettingsResponse>() {
                @Override
                public void onComplete(Task<LocationSettingsResponse> task) {
                    try {
                        LocationSettingsResponse response = task.getResult(ApiException.class);
                        // All location settings are satisfied.
                        startPendingListeners();
                    } catch (ApiException exception) {
                        if (exception.getStatusCode() != LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                            startPendingListeners();
                        } else {
                            // Location settings could be fixed by showing a dialog.
                            try {
                                // Cast to a resolvable exception.
                                ResolvableApiException resolvable = (ResolvableApiException) exception;
                                // Show the dialog by calling startResolutionForResult(),
                                // and check the result in onActivityResult().
                                cordova.setActivityResultCallback(Geolocation.this);
                                resolvable.startResolutionForResult(cordova.getActivity(), REQUEST_LOCATION_ACCURACY_CODE);
                            } catch (Exception e) {
                                startPendingListeners();
                            }
                        }
                    }
                }
            });
    }

    private void startPendingListeners() {
        for (String id : this.watchers.keySet()) {
            LocationRequest request = this.requests.get(id);
            LocationCallback callback = this.watchers.get(id);

			LOG.d(TAG, "startPendingListeners " + callback);
			
            this.locationsClient.requestLocationUpdates(request, callback, Looper.getMainLooper());
        }

        if (this.locationCallbacks.size() > 0) {
            this.locationsClient.getLastLocation()
                .addOnCompleteListener(cordova.getActivity(), Geolocation.this);
        }
    }

    @CordovaMethod
    private void getLocation(boolean enableHighAccuracy, int maxAge, CallbackContext callbackContext) {
		
		LOG.d(TAG, "getLocation");
		
        if (enableHighAccuracy && isGPSdisabled()) {
			
			LOG.d(TAG, "POSITION_UNAVAILABLE");
			
            callbackContext.error(createErrorResult(POSITION_UNAVAILABLE));
        } else {
			
		
            this.locationCallbacks.add(callbackContext);

            if (hasLocationPermission()) {
                this.locationsClient.getLastLocation()
                    .addOnCompleteListener(cordova.getActivity(), this);
            }
        }
    }

    @CordovaMethod
    private void addWatch(String id, boolean enableHighAccuracy, final CallbackContext callbackContext) {
        LocationRequest request = new LocationRequest();
		
		LOG.d(TAG, "addWatch");
		
        if (enableHighAccuracy) {
			
			LOG.d(TAG, "enableHighAccuracy");
			
            if (hasLocationPermission() && isGPSdisabled()) {
				
				LOG.d(TAG, "hasLocationPermission, isGPSdisabled");
				
                callbackContext.error(createErrorResult(POSITION_UNAVAILABLE));
                return;
            }
			
			LOG.d(TAG, "PRIORITY_HIGH_ACCURACY");
			
            request.setInterval(100);
            request.setSmallestDisplacement(0);
            request.setNumUpdates(10);
            request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        } else {
			
			LOG.d(TAG, "PRIORITY_BALANCED_POWER_ACCURACY");
			
            request.setInterval(5000);
            request.setSmallestDisplacement(10);
            request.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
        }
		
		
        LocationCallback locationCallback = new LocationCallback() {
			
	 
            public void onLocationAvailability(LocationAvailability locationAvailability) {
                LOG.d(TAG, "onLocationAvailability");

                if (!locationAvailability.isLocationAvailable()) {
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR, createErrorResult(POSITION_UNAVAILABLE));
                    pluginResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(pluginResult);
                }
            }

            
            public void onLocationResult(LocationResult result) {
                LOG.d("GeolocationPluginFixed", "onLocationResult");

                Location location = result.getLastLocation();
                if (location != null) {
					
					LOG.d(TAG, "onLocationResult "+location );
					
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, createResult(location));
                    pluginResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(pluginResult);
                }
            }
        };

        this.requests.put(id, request);
        this.watchers.put(id, locationCallback);

        if (hasLocationPermission()) {
            this.locationsClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        }
    }

    @CordovaMethod
    private void clearWatch(String id, CallbackContext callbackContext) {
		
		LOG.d(TAG, "clearWatch" );
		
        LocationCallback locationCallback = this.watchers.get(id);
        if (locationCallback != null) {
            this.watchers.remove(id);
            if (hasLocationPermission()) {
                this.locationsClient.removeLocationUpdates(locationCallback);
            }
        }

        callbackContext.success();
    }

    private boolean isGPSdisabled() {
		
		LOG.d(TAG, "GPS_PROVIDER "+this.locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
		LOG.d(TAG, "NETWORK_PROVIDER "+this.locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
		LOG.d(TAG, "PASSIVE_PROVIDER "+this.locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER));
		
		
        return !this.locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);

        if (this.locationsClient != null) {
            for (LocationCallback callback : this.watchers.values()) {
                this.locationsClient.removeLocationUpdates(callback);
            }
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);

        if (this.locationsClient != null) {
            for (String id : this.watchers.keySet()) {
                LocationRequest request = this.requests.get(id);
                LocationCallback callback = this.watchers.get(id);

                this.locationsClient.requestLocationUpdates(request, callback, Looper.getMainLooper());
            }
        }
    }

    @Override
    public void onComplete(Task<Location> task) {
        if (task.isSuccessful()) {
            LOG.d(TAG, "Got last location");

            Location location = task.getResult();
			
			LOG.d(TAG, "location" + location);
            if (location != null) {
                JSONObject result = createResult(location);
                for (CallbackContext callback : this.locationCallbacks) {
                    callback.success(result);
                }
            }
        } else {
            LOG.e(TAG, "Fail to get last location");

            String errorMessage = task.getException().getMessage();
            for (CallbackContext callback : this.locationCallbacks) {
                callback.error(errorMessage);
            }
        }

        this.locationCallbacks.clear();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_LOCATION_ACCURACY_CODE) {
            startPendingListeners();
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                LOG.d(TAG, "Permission Denied!");
                return;
            }
        }

        initLocationClient();
    }

    private static JSONObject createResult(Location loc) {
        JSONObject result = new JSONObject();

        try {
            result.put("timestamp", loc.getTime());
            result.put("velocity", loc.getSpeed());
            result.put("accuracy", loc.getAccuracy());
            result.put("heading", loc.getBearing());
            result.put("altitude", loc.getAltitude());
            result.put("latitude", loc.getLatitude());
            result.put("longitude", loc.getLongitude());

            return result;
        } catch (JSONException e) {
            LOG.e(TAG, "Fail to convert");

            return null;
        }
    }

    private static JSONObject createErrorResult(int code) {
        JSONObject result = new JSONObject();

        try {
            result.put("code", code);

            return result;
        } catch (JSONException e) {
            LOG.e(TAG, "Fail to convert");

            return null;
        }
    }

    public boolean hasLocationPermission() {
        for (String p : permissions) {
            if (!PermissionHelper.hasPermission(this, p)) {
                return false;
            }
        }
        return true;
    }

    /*
     * We override this so that we can access the permissions variable, which no longer exists in
     * the parent class, since we can't initialize it reliably in the constructor!
     */

    public void requestPermissions(int requestCode) {
        PermissionHelper.requestPermissions(this, requestCode, permissions);
    }
}

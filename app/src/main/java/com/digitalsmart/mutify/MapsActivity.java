package com.digitalsmart.mutify;

import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.digitalsmart.mutify.util.BlurController;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.*;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.widget.AutocompleteSupportFragment;
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.valkriaine.factor.HomePager;
import no.danielzeller.blurbehindlib.BlurBehindLayout;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;


public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, LocationListener
{

    private SlidingUpPanelLayout drawer;
    private BlurBehindLayout blurBackground;
    private GoogleMap map;
    private HomePager homePager;






    private LatLng currentLatLng;
    private UserLocation markerUserLocation;
    private Location currentLocation;
    private Location markerLocation;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private FusedLocationProviderClient fusedLocationProviderClient;




    private PermissionManager permissionManager;


    //initialize view model
    private final UserDataManager userDataManager = new UserDataManager();


    private SpringAnimation dragSpring;
    private SpringAnimation settleSpring;



    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        //check permission
        permissionManager = new PermissionManager(this);
        permissionManager.checkPermission();

        initializeComponents();


        //configure RecyclerView
        RecyclerView locationList = findViewById(R.id.recyclerview);
        locationList.setLayoutManager(new LinearLayoutManager(this));


        //initialize Google Maps fragment
        SupportMapFragment mapFragment = (SupportMapFragment) this.getSupportFragmentManager().findFragmentById(R.id.map);
        assert mapFragment != null;
        mapFragment.getMapAsync(this);

        //todo: remove this
        //populating user location list with dummy data
        userDataManager.generateDummyData();

        //accessing the recyclerview adapter via UserDataManager
        locationList.setAdapter(userDataManager.getAdapter());


        //initialize fused location provider
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);


        //must call these methods when implementing background service
        //remove these method calls if Mutify does not need to constantly update the user's location
        initializeLocationCallBack();
        createLocationRequest();
        startLocationUpdates();


        //retrieve user's current location at app launch
        getCurrentLocation(null);
    }




    //call these methods to start constantly updating the user's location info
    //as long as the app is still running (in the foreground or minimized)
    //app will stop updating location info if the user manually closes the app
    //this is not the same as background service, implement background service separately
    //*********************************************************************************************************************
    protected void createLocationRequest()
    {
        locationRequest = LocationRequest.create();
        locationRequest.setInterval(10000);
        locationRequest.setFastestInterval(5000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }
    protected void startLocationUpdates()
    {
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }
    protected void initializeLocationCallBack()
    {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    // Update UI with location data
                    // ...
                }
            }
        };
    }






    //button click events
    //*********************************************************************************************************************
    //call this method to manually get the user's current location
    public void getCurrentLocation(View view)
    {
        if (fusedLocationProviderClient != null)
            fusedLocationProviderClient.getLastLocation().addOnSuccessListener(this, location -> {
                // Got last known location. In some rare situations this can be null.
                if (location != null) {
                    currentLocation = location;
                    currentLatLng = new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude());
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15));
                }
            });
    }


    //open the bottom drawer and slide to the RecyclerView page
    public void menuButtonClicked(View view)
    {
        //add the current marker location to the list
        drawer.setPanelState(SlidingUpPanelLayout.PanelState.EXPANDED);
        homePager.setCurrentItem(1,true);
    }

    //todo: change this after modifying activity_maps.xml
    //open the bottom drawer and slide to the edit page
    public void addButtonClicked(View view)
    {
        //test methods to display the location info of the current marker location
        drawer.setPanelState(SlidingUpPanelLayout.PanelState.EXPANDED);
        homePager.setCurrentItem(0,true);
        TextView name = findViewById(R.id.add_name);
        TextView country = findViewById(R.id.add_country);
        TextView locality = findViewById(R.id.add_locality);

        UserLocation l = markerUserLocation;

        if (l!= null)
        {
            name.setText(l.getName());
            country.setText(l.getCountry());
            locality.setText(l.getLocality());
        }
    }

    //todo: change this, this is a dummy method to add the current marker location to the list
    public void confirmButtonClicked(View view)
    {
        //add the current marker location to the list
        userDataManager.add(markerUserLocation);
        homePager.setCurrentItem(1, true);
    }








    //override methods
    //*********************************************************************************************************************
    //initialize Google Maps
    @Override
    public void onMapReady(GoogleMap googleMap)
    {
        map = googleMap;
        configureCamera();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        permissionManager.checkPermission();
        blurBackground.disable();
    }

    @Override
    protected void onRestart()
    {
        super.onRestart();
        permissionManager.checkPermission();
        blurBackground.disable();
    }

    //required for API26 to work
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras)
    {
        //do nothing
    }

    //called when user's location changes
    @Override
    public void onLocationChanged(@NonNull Location location)
    {

    }

    //called when the location service provider is disabled
    @Override
    public void onProviderDisabled(@NonNull String provider)
    {
        permissionManager.onProviderDisabled();
    }

    //called when the location service provider is enabled
    @Override
    public void onProviderEnabled(@NonNull String provider)
    {
        permissionManager.onProviderEnabled();
        getCurrentLocation(null);
    }

    //handles permission request responses
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull @NotNull String[] permissions, @NonNull @NotNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        int result = permissionManager.onRequestPermissionsResult(requestCode, grantResults);
        if (result == 0)
        {
            getCurrentLocation(null);
            return;
        }
        if (result == 1)
        {
            Toast.makeText(this.getApplicationContext(),
                    R.string.notify_permission,
                    Toast.LENGTH_SHORT)
                    .show();
        }
        if (result == 2)
        {
            Toast.makeText(this.getApplicationContext(),
                    "Mutify needs to access your location in the background, please allow access location all the time.",
                    Toast.LENGTH_SHORT)
                    .show();
        }
    }





    //add other methods here
    //*********************************************************************************************************************
    //link layout components and initialize places sdk, find views by id
    private void initializeComponents()
    {
        // Initialize the SDK
        Places.initialize(getApplicationContext(), this.getResources().getString(R.string.maps_api_key));
        AutocompleteSupportFragment autocompleteFragment = (AutocompleteSupportFragment) getSupportFragmentManager().findFragmentById(R.id.autocomplete_fragment);
        assert autocompleteFragment != null;
        autocompleteFragment.setPlaceFields(Arrays.asList(Place.Field.LAT_LNG, Place.Field.ID, Place.Field.NAME));
        autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener()
        {
            @Override
            public void onPlaceSelected(@NotNull Place place)
            {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(place.getLatLng(), 15));
            }

            @Override
            public void onError(@NotNull Status status)
            {
                Log.i("places_error", "An error occurred: " + status);
            }
        });



        //initialize views
        drawer = findViewById(R.id.drawer);
        homePager = findViewById(R.id.home_pager);
        homePager.addView(findViewById(R.id.add_page), 0);
        homePager.addView(findViewById(R.id.list_page), 1);
        blurBackground = findViewById(R.id.blur_background);
        ImageView marker = findViewById(R.id.marker_sprite);
        CardView addTile = findViewById(R.id.add_tile);
        CardView menuTile = findViewById(R.id.menu_tile);


        //set up blur effect and transition animations
        BlurController blurController = new BlurController(findViewById(R.id.background), blurBackground, addTile, menuTile, homePager);
        drawer.addPanelSlideListener(blurController);
        homePager.addOnPageChangeListener(blurController);


        //spring animations for map marker
        dragSpring = new SpringAnimation(marker, DynamicAnimation.TRANSLATION_Y, -30);
        settleSpring = new SpringAnimation(marker, DynamicAnimation.TRANSLATION_Y, 0);
    }
    //enable the app to retrieve the marker's location
    private void configureCamera()
    {
        GoogleMap.OnCameraIdleListener onCameraIdleListener = () -> {
            dragSpring.skipToEnd();
            settleSpring.start();
            LatLng latLng = map.getCameraPosition().target;
            Geocoder geocoder = new Geocoder(MapsActivity.this);
            markerLocation = new Location("Camera Location");
            markerLocation.setLatitude(latLng.latitude);
            markerLocation.setLongitude(latLng.longitude);
            List<Address> addressList = null;
            try {
                addressList = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (addressList != null && addressList.size() > 0) {
                markerUserLocation = new UserLocation("Marker Location", addressList);
            }
        };

        GoogleMap.OnCameraMoveStartedListener onCameraMoveStartedListener = i -> dragSpring.start();
        map.setOnCameraIdleListener(onCameraIdleListener);
        map.setOnCameraMoveStartedListener(onCameraMoveStartedListener);
    }
}
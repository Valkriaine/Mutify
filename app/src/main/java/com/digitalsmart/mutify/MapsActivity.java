package com.digitalsmart.mutify;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.location.Address;
import android.location.Location;
import android.location.LocationListener;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.fragment.app.FragmentActivity;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.digitalsmart.mutify.Services.FetchAddressJobIntentService;
import com.digitalsmart.mutify.Services.LocationWorker;
import com.digitalsmart.mutify.databinding.ActivityMapsBinding;
import com.digitalsmart.mutify.model.UserLocation;
import com.digitalsmart.mutify.uihelper.BlurController;
import com.digitalsmart.mutify.util.PermissionManager;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
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
import com.skydoves.balloon.ArrowConstraints;
import com.skydoves.balloon.ArrowOrientation;
import com.skydoves.balloon.Balloon;
import com.skydoves.balloon.BalloonAnimation;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.yanzhenjie.recyclerview.OnItemMenuClickListener;
import com.yanzhenjie.recyclerview.SwipeMenuCreator;
import com.yanzhenjie.recyclerview.SwipeMenuItem;
import com.yanzhenjie.recyclerview.SwipeRecyclerView;
import nl.bryanderidder.themedtogglebuttongroup.ThemedButton;
import org.jetbrains.annotations.NotNull;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.digitalsmart.mutify.util.Constants.*;


public class MapsActivity extends FragmentActivity implements
        OnMapReadyCallback,
        LocationListener,
        EasyPermissions.PermissionCallbacks,
        EasyPermissions.RationaleCallbacks
{
    private GoogleMap map;
    private LatLng currentLatLng;
    private UserLocation markerUserLocation;
    private Location currentLocation;
    private Location markerLocation;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private AddressResultReceiver addressResultReceiver;
    private GeofencingClient geofencingClient;
    private PeriodicWorkRequest periodicWorkRequest;
    private BlurController blurController;
    private View.OnClickListener addTileOnClickListener;


    private PermissionManager permissionManager;
    private UserDataManager userDataManager;


    private SpringAnimation dragSpring;
    private SpringAnimation settleSpring;
    private Balloon balloon;

    private boolean isFromLaunch = true;

    private final float[] radiusSliderPosition = {0f};
    private final float[] delaySliderPosition = {0f};
    private boolean radiusHasText = false;
    private String[] locationPermissions;

    //data binding object from activity_maps.xml
    private ActivityMapsBinding binding;


    //receive Geocoder fetch address result
    private class AddressResultReceiver extends ResultReceiver
    {
        //constructor
        AddressResultReceiver(Handler handler) {
            super(handler);
        }

        //Receives data sent from FetchAddressJobIntentService and updates the balloon
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData)
        {
            if (resultCode == SUCCESS_ADDRESS)
            {
                Address a = resultData.getParcelable(ADDRESS);
                if (a != null)
                    markerUserLocation.updateAddress(a);
                updateBalloon(null, true);
            }
            else if (resultCode == FAILURE_RESULT)
            {
                //show the error message
                updateBalloon(resultData.getString(RESULT_DATA_KEY), false);
            }
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        //initialize data binding
        binding = DataBindingUtil.setContentView(this, R.layout.activity_maps);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            locationPermissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION};
        else
            locationPermissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};

        //initialize Google Ads
        MobileAds.initialize(this, initializationStatus -> {});
        AdRequest adRequest = new AdRequest.Builder().build();
        binding.adView.loadAd(adRequest);

        if (isFirstTime())
        {
            AlertDialog permissionDialog = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert).create();
            permissionDialog.setMessage(getString(R.string.prominent_disclosure));
            permissionDialog.show();
            permissionDialog.setCanceledOnTouchOutside(true);
            permissionDialog.setOnCancelListener(dialog -> new PermissionManager(this, locationPermissions).checkPermission());
        }
        else
        {
            //check permission
            permissionManager = new PermissionManager(this, locationPermissions);
            permissionManager.checkPermission();
            permissionManager.checkDNDAccess();
        }


        //register Geocoder intent listener
        addressResultReceiver = new AddressResultReceiver(new Handler(Looper.getMainLooper()));

        //initialize geofence client
        geofencingClient = LocationServices.getGeofencingClient(this);

        initializeComponents();


        //initialize Google Maps fragment
        SupportMapFragment mapFragment = (SupportMapFragment) this.getSupportFragmentManager().findFragmentById(R.id.map);
        assert mapFragment != null;
        mapFragment.getMapAsync(this);

        //retrieve user's current location at app launch
        getCurrentLocation(null);
        //startPeriodicWork();
    }



    //button click events
    //*********************************************************************************************************************
    //call this method to manually get the user's current location
    public void getCurrentLocation(View view)
    {
        if (permissionManager != null)
            permissionManager.checkPermission();

        if (fusedLocationProviderClient != null)
        {
            //if the app just launched and this method is being called in onCreate, get the last known location
            //to avoid delay
            if (isFromLaunch)
                fusedLocationProviderClient.getLastLocation().addOnSuccessListener(this, location -> {
                    if (location != null)
                    {
                        currentLocation = location;
                        currentLatLng = new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude());
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15));
                    }
                    isFromLaunch = false;
                });
            else
                fusedLocationProviderClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener(this, location -> {
                            if (location != null)
                            {
                                currentLocation = location;
                                currentLatLng = new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude());
                                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15));
                            }
                        });
            }
    }

    //open the bottom drawer and slide to the RecyclerView page
    public void menuButtonClicked(View view)
    {
        //add the current marker location to the list
        binding.drawer.setPanelState(SlidingUpPanelLayout.PanelState.EXPANDED);
        binding.homePager.setCurrentItem(1, true);
    }


    public void confirmButtonClicked(View view)
    {
        if (permissionManager == null)
            permissionManager = new PermissionManager(this, locationPermissions);
        permissionManager.checkPermission();
        if (permissionManager.checkDNDAccess())
        {
            if (markerUserLocation != null)
            {
                markerUserLocation.setRadius(Float.parseFloat(String.valueOf(binding.radius.getText())));
                if (String.valueOf(binding.addName.getText()).isEmpty())
                    markerUserLocation.setName("Location " + (userDataManager.getAdapter().getItemCount() + 1));
                else
                    markerUserLocation.setName(String.valueOf(binding.addName.getText()));

                //set transition type
                if (binding.dwelling.isSelected())
                {
                    markerUserLocation.setTransition(Geofence.GEOFENCE_TRANSITION_DWELL);
                    int delay = Integer.parseInt(Objects.requireNonNull(binding.delaySlider.getBubbleText()));
                    markerUserLocation.setDelay(delay * 60000);
                }
                else if (binding.entering.isSelected())
                {
                    markerUserLocation.setTransition(Geofence.GEOFENCE_TRANSITION_ENTER);
                }



                userDataManager.add(markerUserLocation);
                binding.homePager.setCurrentItem(1, true);
            }
        }
    }

    //launch settings_icon activity
    public void launchSettings(View view)
    {
        binding.privacyPolicyPage.setVisibility(View.VISIBLE);
        binding.drawer.setTouchEnabled(false);
        binding.addTile.setOnClickListener(v -> {});
    }



    //override methods
    //*********************************************************************************************************************
    //initialize Google Maps
    @Override
    public void onMapReady(GoogleMap googleMap)
    {
        map = googleMap;
        configureCamera();
        userDataManager = new UserDataManager(map, geofencingClient, this);
        binding.locationList.setAdapter(userDataManager.getAdapter());
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        blurController.setFromBackPress(true);
        binding.drawer.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        if (isDNDAccessRequested())
            new PermissionManager(this, locationPermissions).checkDNDAccess();

        blurController.setFromBackPress(false);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        blurController.setFromBackPress(true);
        binding.drawer.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        if (isDNDAccessRequested())
            new PermissionManager(this, locationPermissions).checkDNDAccess();

        blurController.setFromBackPress(false);

    }


    //required for API26 to work
    @SuppressWarnings("deprecation")
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
        if (permissionManager == null)
            permissionManager = new PermissionManager(this, locationPermissions);
        permissionManager.onProviderDisabled();
    }

    //called when the location service provider is enabled
    @Override
    public void onProviderEnabled(@NonNull String provider)
    {
        if (permissionManager == null)
            permissionManager = new PermissionManager(this, locationPermissions);
        permissionManager.onProviderEnabled();
        getCurrentLocation(null);
    }

    //handles permission request responses
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull @NotNull String[] permissions, @NonNull @NotNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (permissionManager == null)
            permissionManager = new PermissionManager(this, locationPermissions);
        permissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults);

    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event)
    {
        if (event.getAction() == MotionEvent.ACTION_DOWN)
        {
            View v = getCurrentFocus();
            if ( v instanceof EditText) {
                Rect outRect = new Rect();
                v.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY()))
                {
                    v.clearFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed()
    {
        if (binding.privacyPolicyPage.getVisibility() == View.VISIBLE)
        {
            binding.privacyPolicyPage.setVisibility(View.GONE);
            binding.drawer.setTouchEnabled(true);
            binding.addTile.setOnClickListener(addTileOnClickListener);
        }
        else
        {
            blurController.setFromBackPress(true);
            if (binding.drawer.getPanelState() == SlidingUpPanelLayout.PanelState.EXPANDED)
                binding.drawer.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
            else
                super.onBackPressed();

            blurController.setFromBackPress(false);
        }
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull @NotNull List<String> perms)
    {
        //retrieve user's current location at app launch
        getCurrentLocation(null);

        if (permissionManager == null)
            permissionManager = new PermissionManager(this, locationPermissions);

        if (EasyPermissions.hasPermissions(this, locationPermissions))
            permissionManager.checkDNDAccess();
        else
            permissionManager.checkPermission();
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull @NotNull List<String> perms)
    {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms))
        {
            new AppSettingsDialog.Builder(this).build().show();
        }
        else
        {
            if (permissionManager == null)
                permissionManager = new PermissionManager(this, locationPermissions);
            permissionManager.checkPermission();
        }
    }

    @Override
    public void onRationaleAccepted(int requestCode)
    {
        if (permissionManager == null)
            permissionManager = new PermissionManager(this, locationPermissions);
        permissionManager.checkPermission();
    }

    @Override
    public void onRationaleDenied(int requestCode)
    {
        //exit the app
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory( Intent.CATEGORY_HOME );
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(homeIntent);
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
            public void onError(@NotNull Status status) {
                Log.i("places_error", "An error occurred: " + status);
            }
        });


        //setup address info balloon
        balloon = new Balloon.Builder(this)
                .setArrowSize(10)
                .setArrowOrientation(ArrowOrientation.BOTTOM)
                .setArrowConstraints(ArrowConstraints.ALIGN_ANCHOR)
                .setArrowPosition(0.5f)
                .setArrowVisible(true)
                .setLayout(R.layout.address_balloon_layout)
                .setBalloonAnimation(BalloonAnimation.OVERSHOOT)
                .setDismissWhenTouchOutside(false)
                .setLifecycleOwner(MapsActivity.this)
                .build();


        //initialize views
        binding.homePager.addView(binding.addPage, 0);
        binding.homePager.addView(binding.listPage, 1);


        //set up blur effect and transition animations
        blurController = new BlurController(
                binding.background,
                binding.blurLayer,
                binding.addTile,
                binding.menuTile,
                binding.homePager,
                balloon,
                binding.spriteOutline,
                getScreenWidth()/8);
        binding.drawer.addPanelSlideListener(blurController);
        binding.homePager.addOnPageChangeListener(blurController);

        //spring animations for map marker sprite
        dragSpring = new SpringAnimation(binding.markerSprite, DynamicAnimation.TRANSLATION_Y, -30);
        settleSpring = new SpringAnimation(binding.markerSprite, DynamicAnimation.TRANSLATION_Y, 0);

        //initialize fused location provider
        //this is for locating the user when the app is running in the foreground only
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);


        //initialize radius slider and radius text input
        binding.radiusSlider.setPositionListener(aFloat -> {
            //do not update UI here
            radiusSliderPosition[0] = (int) (500 * aFloat);
            binding.radius.setText(String.valueOf((int) radiusSliderPosition[0]));
            binding.radiusSlider.setBubbleText(String.valueOf((int) radiusSliderPosition[0]));
            checkCanConfirm();
            return null;
        });
        binding.radiusSlider.setEndTrackingListener(() -> {
            binding.radiusSlider.setPosition(radiusSliderPosition[0] /500);
            //update UI here
            binding.radius.setText(String.valueOf((int) radiusSliderPosition[0]));
            binding.radiusSlider.setBubbleText(String.valueOf((int) radiusSliderPosition[0]));
            checkCanConfirm();
            return null;
        });
        binding.radius.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2)
            {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2)
            {

            }

            @Override
            public void afterTextChanged(Editable editable)
            {
                if (editable.length() < 1)
                {
                    Toast.makeText(MapsActivity.this, "Please specify a radius", Toast.LENGTH_SHORT).show();
                    radiusHasText = false;
                }
                else if (Float.parseFloat(String.valueOf(editable)) > 500)
                {
                    Toast.makeText(MapsActivity.this, "Please enter a value between 0 and 500", Toast.LENGTH_SHORT).show();
                    binding.radius.setText("500");
                    radiusHasText = true;
                }
                else
                {
                    binding.radiusSlider.setBubbleText(String.valueOf(Integer.parseInt(String.valueOf(editable))));
                    radiusHasText = true;
                }
                checkCanConfirm();
            }
        });
        binding.radiusSlider.setPosition(80f/500);


        //initialize delay slider
        binding.delaySlider.setPositionListener(aFloat -> {
            //do not update UI here
            delaySliderPosition[0] = (int) (4 * aFloat + 1);
            binding.delaySlider.setBubbleText(String.valueOf((int) delaySliderPosition[0]));
            binding.delayText.setText( (int) delaySliderPosition[0] + " min delay");
            checkCanConfirm();
            return null;
        });
        binding.delaySlider.setEndTrackingListener(() -> {
            binding.delaySlider.setPosition((delaySliderPosition[0]-1)/4);
            //update UI here
            binding.delaySlider.setBubbleText(String.valueOf((int) delaySliderPosition[0]));
            binding.delayText.setText( (int) delaySliderPosition[0] + " min delay");
            checkCanConfirm();
            return null;
        });
        binding.delaySlider.setPosition(0.5f);

        binding.transitionType.setOnSelectListener((ThemedButton btn) -> {
            if (btn.getSelectedText().equals("Entering"))
            {
                binding.transitionInfo.setText(R.string.entering_info);
                binding.delaySlider.setVisibility(View.INVISIBLE);
                binding.delayText.setVisibility(View.INVISIBLE);
            }
            else if (btn.getSelectedText().equals("Dwelling"))
            {
                binding.transitionInfo.setText(R.string.dwelling_info);
                binding.delaySlider.setVisibility(View.VISIBLE);
                binding.delayText.setVisibility(View.VISIBLE);
            }

            checkCanConfirm();
            return null;
        });

        // create swipe menu
        SwipeMenuCreator menuCreator = (leftMenu, rightMenu, position) -> {
            SwipeMenuItem deleteItem = new SwipeMenuItem(MapsActivity.this)
                    .setBackground(R.drawable.background_red)
                    .setText("Delete")
                    .setTextColor(Color.WHITE);
            rightMenu.addMenuItem(deleteItem);
        };


        OnItemMenuClickListener mItemMenuClickListener = (menuBridge, position) -> {
            menuBridge.closeMenu();
            int direction = menuBridge.getDirection();
            int menuPosition = menuBridge.getPosition();
            if (direction == SwipeRecyclerView.RIGHT_DIRECTION && menuPosition == 0)
            {
                userDataManager.remove(position);
            }
        };

        binding.locationList.setSwipeMenuCreator(menuCreator);
        binding.locationList.setOnItemMenuClickListener(mItemMenuClickListener);

        binding.privacyPolicy.loadUrl("file:///android_asset/privacy_policy.html");

        addTileOnClickListener = v -> {
            if (permissionManager == null)
                permissionManager = new PermissionManager(MapsActivity.this, locationPermissions);
            permissionManager.checkPermission();

            binding.drawer.setPanelState(SlidingUpPanelLayout.PanelState.EXPANDED);
            binding.homePager.setCurrentItem(0, true);
            binding.radiusSlider.setPosition(80f/500);
            binding.delaySlider.setPosition(0.5f);
            binding.addName.setText("Location " + (userDataManager.getAdapter().getItemCount() + 1));
            binding.transitionType.selectButton(R.id.entering);
            if (blurController != null)
                blurController.setAddButtonClicked(true);
        };

        binding.addTile.setOnClickListener(addTileOnClickListener);
    }

    //show or hide the confirm button
    private void checkCanConfirm()
    {
        if (radiusSliderPosition[0] > 0 && radiusHasText && binding.transitionType.getSelectedButtons().size() > 0)
            binding.addConfirmButton.setVisibility(View.VISIBLE);
        else
            binding.addConfirmButton.setVisibility(View.INVISIBLE);
    }

    //get the device's screen width in pixels
    @SuppressWarnings("deprecation")
    public int getScreenWidth()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        {
            WindowMetrics windowMetrics = getWindowManager().getCurrentWindowMetrics();
            Insets insets = windowMetrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            return windowMetrics.getBounds().width() - insets.left - insets.right;
        }
        else
        {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            return displayMetrics.widthPixels;
        }
    }


    //enable the app to retrieve the marker's location
    private void configureCamera()
    {
        GoogleMap.OnCameraIdleListener onCameraIdleListener = () ->
        {
            dragSpring.skipToEnd();
            settleSpring.start();
            LatLng latLng = map.getCameraPosition().target;
            markerLocation = new Location("Camera Location");
            markerLocation.setLatitude(latLng.latitude);
            markerLocation.setLongitude(latLng.longitude);

            markerUserLocation = new UserLocation("Marker Location", markerLocation);

            // Create an intent for passing to the intent service responsible for fetching the address.
            Intent intent = new Intent(this, FetchAddressJobIntentService.class);
            // Pass the result receiver as an extra to the service.
            intent.putExtra(RECEIVER, addressResultReceiver);
            //pass the marker location
            intent.putExtra(LOCATION_DATA_EXTRA, markerLocation);
            FetchAddressJobIntentService.enqueueWork(MapsActivity.this, intent);
            balloon.showAlignTop(binding.spriteOutline);

        };

        GoogleMap.OnCameraMoveStartedListener onCameraMoveStartedListener = i ->
        {
            dragSpring.start();
            if (balloon != null)
                balloon.dismiss();

        };

        map.setOnCameraIdleListener(onCameraIdleListener);
        map.setOnCameraMoveStartedListener(onCameraMoveStartedListener);
    }

    //update the information displayed in the balloon based on the Geocoder fetching result
    private void updateBalloon(String message, boolean isSuccessful)
    {
        if (markerUserLocation != null && balloon != null)
        {
            TextView address = balloon.getContentView().findViewById(R.id.address);
            TextView lat = balloon.getContentView().findViewById(R.id.lat);
            TextView lng = balloon.getContentView().findViewById(R.id.lng);
            if (isSuccessful)
            {
                address.setText(markerUserLocation.getAddressLine());
                lat.setText("Latitude: " + markerUserLocation.getLatitude());
                lng.setText("Longitude: " + markerUserLocation.getLongitude());
            }
            else
            {
                address.setText(message);
                lat.setText("You may still edit this location and add it to your list.");
                lng.setText("It simply won't contain address info. ");
            }
            balloon.update(binding.spriteOutline);
            if (binding.drawer.getPanelState() == SlidingUpPanelLayout.PanelState.EXPANDED)
                balloon.dismiss();
        }
    }

    //check if first time launch
    private boolean isFirstTime()
    {
        SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
        boolean ranBefore = preferences.getBoolean(PACKAGE_NAME + "_RanBefore", false);
        if (!ranBefore)
        {
            // first time
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(PACKAGE_NAME + "_RanBefore", true);
            editor.commit();
        }
        return !ranBefore;
    }

    //check if DND access has been requested
    private boolean isDNDAccessRequested()
    {
        SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
        return preferences.getBoolean(PACKAGE_NAME + "_DND_Requested", false);
    }






















    //todo: this doesn't work
    //start updating location in the background
    private void startPeriodicWork()
    {
        WorkManager workManager = WorkManager.getInstance(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            periodicWorkRequest = new PeriodicWorkRequest.Builder(LocationWorker.class, 5, TimeUnit.SECONDS).build();
        }
        workManager.enqueue(periodicWorkRequest);
    }
}
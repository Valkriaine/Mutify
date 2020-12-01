package com.digitalsmart.mutify.util;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.digitalsmart.mutify.MapsActivity;
import com.digitalsmart.mutify.R;
import org.jetbrains.annotations.NotNull;
import pub.devrel.easypermissions.EasyPermissions;
import pub.devrel.easypermissions.PermissionRequest;

import static com.digitalsmart.mutify.util.Constants.LOCATION_REQUEST_CODE;
import static com.digitalsmart.mutify.util.Constants.PACKAGE_NAME;


//manages all location related operations, including permission requests
public class PermissionManager
{
    private final MapsActivity mapsActivity;
    private LocationManager locationManager;
    private AlertDialog dndDialog;
    private final String[] locationPermissions;

    //constructor
    public PermissionManager(MapsActivity activity, String[] locationPermissions)
    {
        mapsActivity = activity;
        checkPermissionAndService();
        this.locationPermissions = locationPermissions;
    }

    //check permission and location service availability
    public void checkPermissionAndService()
    {
        if(!isLocationEnabled(this.mapsActivity))
        {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            this.mapsActivity.startActivity(intent);
        }
    }

    //check if location service is currently enabled
    @SuppressWarnings("deprecation")
    public Boolean isLocationEnabled(Context context)
    {
        if (locationManager == null)
            locationManager = (LocationManager) mapsActivity.getSystemService(Context.LOCATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            return locationManager.isLocationEnabled();
        }
        else
        {
            int mode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF);
            return  (mode != Settings.Secure.LOCATION_MODE_OFF);
        }
    }

    //invoked when location provider is disabled
    public void onProviderDisabled()
    {
        Toast.makeText(mapsActivity.getApplicationContext(),
                R.string.notify_location_service,
                Toast.LENGTH_LONG)
                .show();
    }

    //invoked when location provider is enabled
    public void onProviderEnabled()
    {
        if(isLocationEnabled(mapsActivity))
        {
            checkPermissionAndService();
        }
        else
            Toast.makeText(mapsActivity.getApplicationContext(),
                    R.string.notify_location_service,
                    Toast.LENGTH_LONG)
                    .show();
    }

    //listen for permission request result
    public void onRequestPermissionsResult(int requestCode, @NonNull @NotNull String[] permissions, @NonNull @NotNull int[] grantResults)
    {
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, mapsActivity);
    }

    //check and ask for permissions
    public void checkPermission()
    {
        if (!EasyPermissions.hasPermissions(mapsActivity, locationPermissions))
        {
            EasyPermissions.requestPermissions(
                    new PermissionRequest.Builder(mapsActivity, LOCATION_REQUEST_CODE, locationPermissions)
                            .setRationale(R.string.notify_permission)
                            .setPositiveButtonText("Okay")
                            .setNegativeButtonText("Cancel")
                            .setTheme(R.style.Theme_AppCompat_DayNight_Dialog_Alert)
                            .build());

        }
    }

    public boolean checkDNDAccess()
    {
        if(!canAccessDND())
        {
            if (dndDialog == null || !dndDialog.isShowing())
            {
                dndDialog = new AlertDialog.Builder(mapsActivity, R.style.Theme_AppCompat_DayNight_Dialog_Alert)
                        .setNegativeButton("Take me there", (dialogInterface, i) -> dndDialog.cancel())
                        .create();
                dndDialog.setMessage("Please allow Mutify to modify do not disturb settings.");
                dndDialog.show();
                dndDialog.setCanceledOnTouchOutside(false);

                dndDialog.setOnCancelListener(dialog -> {
                    if(!canAccessDND())
                    {
                        SharedPreferences.Editor editor = mapsActivity.getPreferences(Context.MODE_PRIVATE).edit();
                        editor.putBoolean(PACKAGE_NAME + "_DND_Requested", true);
                        editor.commit();
                        mapsActivity.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    }
                    else
                    {
                        dndDialog.cancel();
                    }
                });
            }
        }
        else
        {
            if (dndDialog != null)
                dndDialog.cancel();
        }

        return canAccessDND();
    }

    private boolean canAccessDND()
    {
        NotificationManager n = (NotificationManager) mapsActivity.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);

        if (n.isNotificationPolicyAccessGranted())
        {
            SharedPreferences.Editor editor = mapsActivity.getPreferences(Context.MODE_PRIVATE).edit();
            editor.putBoolean(PACKAGE_NAME + "_DND_Requested", false);
            editor.commit();
            if (dndDialog != null)
                dndDialog.cancel();

        }

        return n.isNotificationPolicyAccessGranted();
    }
}

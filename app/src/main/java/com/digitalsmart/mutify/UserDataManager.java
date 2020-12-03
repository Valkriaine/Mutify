package com.digitalsmart.mutify;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import com.digitalsmart.mutify.broadcast_receivers.GeofenceBroadcastReceiver;
import com.digitalsmart.mutify.database.UserLocationDatabase;
import com.digitalsmart.mutify.model.UserLocation;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.Task;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

import static com.digitalsmart.mutify.util.Constants.NOTIFICATION_ID;
import static com.digitalsmart.mutify.util.Constants.PACKAGE_NAME;

//ViewModel class for managing user data in a singleton manner
//create an instance of UserDataManager when app starts
//call the methods below to access/modify/save user data
public class UserDataManager
{
    private final ArrayList <UserLocation> locations = new ArrayList<>();
    private final ArrayList<String> locationsToRemove = new ArrayList<>();
    private final ArrayList<Geofence> fences = new ArrayList<>();
    private final ArrayList<Geofence> fencesToAdd = new ArrayList<>();

    private LocationsAdapter adapter;
    private GoogleMap map;
    private final GeofencingClient geofencingClient;
    private PendingIntent geofencePendingIntent;
    private Activity activity;
    private final UserLocationDatabase db;


    //constructor of the view model
    //add anything in the constructor to be initialized when the view model is created
    public UserDataManager(GoogleMap map, GeofencingClient client, Activity activity)
    {
        this.map = map;
        this.activity = activity;
        this.geofencingClient = client;
        adapter = new LocationsAdapter();

        db = Room.databaseBuilder(activity.getApplicationContext(), UserLocationDatabase.class, "user-locations").build();

        getUserData();
    }

    //lightweight constructor for restoring fences after reboot
    @SuppressLint("MissingPermission")
    public UserDataManager(Context context)
    {
        this.geofencingClient = LocationServices.getGeofencingClient(context);
        db = Room.databaseBuilder(context, UserLocationDatabase.class, "user-locations").build();
    }

    //restore fences after reboot, call this in RebootBroadcastReceiver
    @SuppressLint("MissingPermission")
    public void addFencesAfterReboot(Context context)
    {
        new Thread(() -> locations.addAll(db.userLocationDAO().getAll())).start();
        createNotificationChannel(context);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, PACKAGE_NAME)
                .setSmallIcon(R.drawable.location_icon)
                .setContentTitle("Mutify")
                .setNotificationSilent()
                .setPriority(NotificationCompat.PRIORITY_LOW);


        SystemClock.sleep(10000);
        generateFences();
        if (fencesToAdd.size() > 0)
            geofencingClient.addGeofences(getGeofencingRequest(), getGeofencePendingIntent(context))
                    .addOnCompleteListener(task ->
                            notificationManager.notify(NOTIFICATION_ID, builder.setAutoCancel(true).setContentText("Mutify service restarted").build()))
                    .addOnFailureListener(exception ->
                            notificationManager.notify(NOTIFICATION_ID, builder.setContentText("Mutify service failed to start, please open the app to manually register fences").build()));


    }

    //call this method to retrieve the location list from a local file or a database
    @SuppressLint("MissingPermission")
    public void getUserData()
    {
        new Thread(() ->
        {
            locations.addAll(db.userLocationDAO().getAll());
            activity.runOnUiThread(() ->
            {
                adapter.notifyDataSetChanged();
                updateMap();
            });
        }).start();
        
        //call generateFences after retrieving all the data (background thread finish)
        generateFences();
        if (fencesToAdd.size() > 0)
            geofencingClient.addGeofences(getGeofencingRequest(), getGeofencePendingIntent(activity.getApplicationContext()))
                    .addOnFailureListener(e ->
                    {
                        Toast.makeText(activity, "Mutify could not restore geo fences.", Toast.LENGTH_SHORT).show();
                        Log.wtf("fences", e.getMessage());
                    });
    }


    //call this method to add a location to the list
    @SuppressLint("MissingPermission")
    public void add(UserLocation location)
    {
        if (locations.size() > 99)
        {
            Toast.makeText(activity, "You can only save max 100 locations.", Toast.LENGTH_SHORT).show();
            Log.d("fences", "number of locations saved: " + locations.size());
            return;
        }

        if (!locations.contains(location))
        {
            fencesToAdd.add(location.getGeofence());
            geofencingClient.addGeofences(getGeofencingRequest(), getGeofencePendingIntent(activity.getApplicationContext()))
                    .addOnSuccessListener(activity, v ->
                    {
                        fences.addAll(fencesToAdd);
                        fencesToAdd.clear();
                        Toast.makeText(activity, location.getName() + " added", Toast.LENGTH_SHORT).show();

                        //draw circle and put marker on the map
                        //if geo fence is successfully added
                        map.addCircle(new CircleOptions()
                                .center(location.getLatLng())
                                .radius(location.getRadius())
                                .strokeColor(Color.RED));
                        map.addMarker(new MarkerOptions().position(location.getLatLng()));
                        locations.add(location);
                        Thread insert = new Thread(() ->
                        {
                            db.userLocationDAO().insert(location);
                            activity.runOnUiThread(adapter::notifyDataSetChanged);
                        });
                        insert.setUncaughtExceptionHandler((t, e) ->
                                Toast.makeText(activity, "Location already added.", Toast.LENGTH_SHORT).show());
                        insert.start();
                    })
                    .addOnFailureListener(e -> Toast
                            .makeText(activity, "Geofencing not available, please turn on location service.", Toast.LENGTH_LONG)
                            .show());
        }
        else
            Toast.makeText(activity, "Location already added.", Toast.LENGTH_SHORT).show();
    }

    //call this method to remove saved location from the list
    public void remove(int adapterPosition)
    {
        UserLocation location = locations.get(adapterPosition);
        fencesToAdd.remove(location.getGeofence());
        locationsToRemove.add(location.getId());
        Task<Void> removal = geofencingClient.removeGeofences(locationsToRemove);
        removal.addOnCompleteListener(task ->
                {
                    locationsToRemove.clear();
                    new Thread(() -> db.userLocationDAO().delete(location)).start();
                    locations.remove(adapterPosition);
                    Toast.makeText(activity, location.getName() + " removed", Toast.LENGTH_SHORT).show();
                    adapter.notifyItemRemoved(adapterPosition);
                    updateMap();
                } );
    }

    //update markers and circles
    private void updateMap()
    {
        new Thread(() -> activity.runOnUiThread(() ->
        {
            map.clear();
            for (UserLocation location: locations)
            {
                map.addCircle(new CircleOptions()
                        .center(location.getLatLng())
                        .radius(location.getRadius())
                        .strokeColor(Color.RED));
            }
        })).start();
    }
    //return the RecyclerView adapter
    public LocationsAdapter getAdapter()
    {
        return this.adapter;
    }

    //retrieve the saved list of geo fences
    private void generateFences()
    {
        locations.forEach(userLocation -> fencesToAdd.add(userLocation.getGeofence()));
        fences.addAll(fencesToAdd);
    }

    //methods needed for adding new geo fences, do not call them directly
    //call add() to add new fences
    private PendingIntent getGeofencePendingIntent(Context context)
    {
        if (geofencePendingIntent != null)
        {
            return geofencePendingIntent;
        }
        Intent intent = new Intent(context, GeofenceBroadcastReceiver.class);
        geofencePendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        return geofencePendingIntent;
    }
    private GeofencingRequest getGeofencingRequest()
    {
        return new GeofencingRequest
                .Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(fencesToAdd)
                .build();
    }

    private void createNotificationChannel(Context context)
    {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            String description = "Mutify app";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(PACKAGE_NAME, PACKAGE_NAME, importance);
            channel.setDescription(description);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }


    //custom adapter for the list
    @SuppressWarnings("rawtypes")
    public class LocationsAdapter extends RecyclerView.Adapter
    {
        @NonNull
        @NotNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType)
        {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.userlocation_list_item, parent, false);
            return new LocationViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull @NotNull RecyclerView.ViewHolder holder, int position)
        {
            UserLocation l = locations.get(position);
            LocationViewHolder locationViewHolder = (LocationViewHolder)holder;


            if (l != null)
            {
                locationViewHolder.name.setText(l.getName());
                locationViewHolder.address.setText(l.getAddressLine());
                locationViewHolder.country.setText(l.getCountry());
                locationViewHolder.zipCode.setText(l.getPostalCode());
            }
        }

        @Override
        public int getItemCount()
        {
            return locations.size();
        }

        //custom ViewHolder for displaying each item in the list
        private class LocationViewHolder extends RecyclerView.ViewHolder
        {

            private final TextView name;
            private final TextView address;
            private final TextView country;
            private final TextView zipCode;
            public LocationViewHolder(View itemView)
            {
                super(itemView);
                name = itemView.findViewById(R.id.item_name);
                address = itemView.findViewById(R.id.item_address);
                country = itemView.findViewById(R.id.item_country);
                zipCode = itemView.findViewById(R.id.item_code);
            }
        }
    }
}

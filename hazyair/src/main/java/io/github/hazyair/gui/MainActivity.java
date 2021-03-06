package io.github.hazyair.gui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationListener;
import android.os.Parcelable;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager.widget.ViewPager;
import butterknife.BindView;
import butterknife.ButterKnife;
import io.fabric.sdk.android.Fabric;
import io.github.hazyair.R;
import io.github.hazyair.data.StationsContract;
import io.github.hazyair.data.StationsLoader;
import io.github.hazyair.service.NotificationService;
import io.github.hazyair.source.Station;
import android.support.v4.app.DatabaseService;

import java.util.concurrent.TimeUnit;

import io.github.hazyair.service.DatabaseSyncService;
import io.github.hazyair.util.GDPR;
import io.github.hazyair.util.HazyairViewPager;
import io.github.hazyair.util.License;
import io.github.hazyair.util.LocationCallbackReference;
import io.github.hazyair.util.Network;
import io.github.hazyair.util.Preference;

import static android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM;
import static io.github.hazyair.util.Location.PERMISSION_REQUEST_FINE_LOCATION;

public class MainActivity extends AppCompatActivity implements
        LoaderManager.LoaderCallbacks<Cursor>, LocationListener {

    // Final definitions
    public static final String PARAM_STATION = "io.github.hazyair.PARAM_STATION";
    public static final String PARAM_EXIT = "io.github.hazyair.PARAM_EXIT";
    private static final int ACTION_REMOVE_STATION = 0xDEADBEEF;

    // Nested classes definitions
    class StationPagerAdapter extends FragmentStatePagerAdapter {

        private Cursor mCursor;
        private final SparseArray<Fragment> mFragments = new SparseArray<>();
        private final FragmentManager mFragmentManager;

        StationPagerAdapter(FragmentManager fm) {
            super(fm);
            mFragmentManager = fm;
        }

        void setCursor(Cursor cursor) {
            mCursor = cursor;
            if (mCursor != null) notifyDataSetChanged();
        }

        Cursor getCursor() {
            return mCursor;
        }

        void removePage(int position) {
            Fragment fragment = mFragments.get(position);
            if (fragment == null) return;
            if (mViewPager != null) destroyItem(mViewPager, position, fragment);
            mFragments.remove(position);
        }

        @Override
        public Fragment getItem(int position) {
            mCursor.moveToPosition(position);
            StationFragment oldFragment = (StationFragment) mFragments.get(position);
            Fragment newFragment = StationFragment.newInstance(mCursor, oldFragment);
            if (oldFragment != null) oldFragment.removeUpdates();
            mFragments.put(position, newFragment);
            return newFragment;
        }

        @Override
        public int getCount() {
            return mCursor == null ? 0 : mCursor.getCount();
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;
        }

        @Override
        public Parcelable saveState() {
            Bundle state = (Bundle) super.saveState();
            for (int i = 0; i < mFragments.size(); i++) {
                Fragment fragment = mFragments.get(i);
                if (fragment != null && fragment.isAdded()) {
                    if (state == null) {
                        state = new Bundle();
                    }
                    String key = StationFragment.class.getName() + i;
                    mFragmentManager.putFragment(state, key, fragment);
                }
            }
            return state;
        }

        @Override
        public void restoreState(Parcelable state, ClassLoader loader) {
            super.restoreState(state, loader);
            if (state == null) return;
            Bundle bundle = (Bundle) state;
            Iterable<String> keys = bundle.keySet();
            if (keys == null) return;
            mFragments.clear();
            for (String key : keys) {
                if (key.startsWith(StationFragment.class.getName())) {
                    int index = Integer.parseInt(key.substring(
                            StationFragment.class.getName().length()));
                    Fragment fragment = mFragmentManager.getFragment(bundle, key);
                    if (fragment != null) {
                        fragment.setMenuVisibility(false);
                        mFragments.put(index, fragment);
                    }
                }
            }
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        @Nullable
        @BindView(R.id.cardview)
        CardView card;

        @Nullable
        @BindView(R.id.place)
        TextView place;

        @Nullable
        @BindView(R.id.address)
        TextView address;

        @Nullable
        @BindView(R.id.station)
        TextView station;

        @Nullable
        @BindView(R.id.distance)
        TextView distance;

        ViewHolder(View itemView) {
            super(itemView);
            ButterKnife.bind(this, itemView);
        }
    }

    class StationListAdapter extends RecyclerView.Adapter<ViewHolder> {

        private Cursor mCursor;
        private int mCurrentItem = 0;
        private ViewHolder mCurrentViewHolder;
        private Location mLocation;
        private boolean mDistance;

        void setCursor(Cursor cursor) {
            mCursor = cursor;
            if (mCursor != null) notifyDataSetChanged();
        }

        Cursor getCursor() {
            return mCursor;
        }

        void setLocation(Location location) {
            mLocation = location;
            notifyDataSetChanged();
        }

        void setDistance(boolean distance) {
            mDistance = distance;
            notifyDataSetChanged();
        }

        void setCurrentItem(int currentItem) {
            mCurrentItem = currentItem;
            notifyDataSetChanged();
        }

        private void selectStation(ViewHolder holder, int position) {
            if (holder.card == null) return;
            holder.card.setCardBackgroundColor(getColor(R.color.primaryLight));
            if (holder.place == null) return;
            holder.place.setTextColor(getColor(R.color.textLighter));
            if (holder.address == null) return;
            holder.address.setTextColor(getColor(R.color.textLight));
            if (holder.station == null) return;
            holder.station.setTextColor(getColor(R.color.textLight));
            if (holder.distance == null) return;
            holder.distance.setTextColor(getColor(R.color.textLight));
            ConstraintLayout.LayoutParams layoutParams =
                    (ConstraintLayout.LayoutParams) mViewPager.getLayoutParams();
            int width = mViewPager.getMeasuredWidth();
            if (width > 0) {
                layoutParams.width = width;
                mViewPager.setLayoutParams(layoutParams);
                mCursor.moveToPosition(position);
                mViewPager.setCurrentItem(position, false);
                mSelectedStation = Station.toBundleFromCursor(mCursor);
                DatabaseService.selectStation(holder.itemView.getContext(),
                        mSelectedStation);
            } else {
                ViewTreeObserver vto = mViewPager.getViewTreeObserver();
                vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mViewPager.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        ConstraintLayout.LayoutParams layoutParams =
                                (ConstraintLayout.LayoutParams) mViewPager.getLayoutParams();
                        layoutParams.width = mViewPager.getMeasuredWidth();
                        mViewPager.setLayoutParams(layoutParams);
                        mCursor.moveToPosition(position);
                        mViewPager.setCurrentItem(position, false);
                        mSelectedStation = Station.toBundleFromCursor(mCursor);
                        DatabaseService.selectStation(holder.itemView.getContext(),
                                mSelectedStation);
                    }
                });
            }
        }

        void deselectStation(ViewHolder viewHolder) {
            if (viewHolder == null) return;
            if (viewHolder.card == null) return;
            viewHolder.card.setCardBackgroundColor(getColor(android.R.color.white));
            if (viewHolder.place == null) return;
            viewHolder.place.setTextColor(getColor(R.color.textDarker));
            if (viewHolder.address == null) return;
            viewHolder.address.setTextColor(getColor(R.color.textDark));
            if (viewHolder.station == null) return;
            viewHolder.station.setTextColor(getColor(R.color.textDark));
            if (viewHolder.distance == null) return;
            viewHolder.distance.setTextColor(getColor(R.color.textDark));
        }

        void deselectCurrentStation() {
            deselectStation(mCurrentViewHolder);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.station, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            int layoutPosition = holder.getLayoutPosition();
            if (mCursor == null || !mCursor.moveToPosition(layoutPosition)) return;
            Bundle station = Station.toBundleFromCursor(mCursor);
            if (holder.place == null) return;
            holder.place.setText(String.format("%s %s",
                    getString(station.getInt(StationsContract.COLUMN_COUNTRY)),
                    station.getString(StationsContract.COLUMN_LOCALITY)));
            if (holder.address == null) return;
            holder.address.setText(station.getString(StationsContract.COLUMN_ADDRESS));
            if (holder.station == null) return;
            holder.station.setText(String.format("%s %s",
                    getString(R.string.text_station_by),
                    getString(station.getInt(StationsContract.COLUMN_SOURCE))));
            if (holder.distance != null) {
                holder.distance.setVisibility(mDistance ? View.VISIBLE : View.GONE);
                if (mDistance && mLocation != null) {
                    Location location = new Location(
                            station.getString(StationsContract.COLUMN_SOURCE));
                    location.setLongitude(
                            station.getDouble(StationsContract.COLUMN_LONGITUDE));
                    location.setLatitude(
                            station.getDouble(StationsContract.COLUMN_LATITUDE));
                    holder.distance.setText(String.format("%s %s",
                            String.valueOf((int) (location.distanceTo(mLocation) / 1000)),
                            getString(R.string.text_km)));
                }
            }
            if (layoutPosition == mCurrentItem) {
                selectStation(holder, layoutPosition);
                mCurrentViewHolder = holder;
            } else {
                deselectStation(holder);
            }
            holder.itemView.setOnClickListener((v) -> {
                if (layoutPosition == mCurrentItem) return;
                deselectCurrentStation();
                selectStation(holder, layoutPosition);
                mCurrentViewHolder = holder;
                mCurrentItem = layoutPosition;
            });
        }

        @Override
        public int getItemCount() {
            return mCursor == null ? 0 : mCursor.getCount();
        }
    }

    // Class members
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case DatabaseService.ACTION_UPDATING:
                    mSwipeRefreshLayout.setRefreshing(true);
                    break;
                case DatabaseService.ACTION_UPDATED:
                    mSwipeRefreshLayout.setRefreshing(false);
                    break;
            }
        }
    };

    private StationPagerAdapter mStationPagerAdapter;

    private StationListAdapter mStationListAdapter;

    private boolean mTwoPane;

    private Menu mMenu;

    private Bundle mSelectedStation;

    private LocationCallback mLocationCallback;

    private LocationRequest mLocationRequest;

    private FusedLocationProviderClient mFusedLocationProviderClient;

    // ButterKnife
    @BindView(R.id.toolbar)
    Toolbar mToolbar;

    @BindView(R.id.view_pager)
    HazyairViewPager mViewPager;

    @BindView(R.id.fab_add_station)
    FloatingActionButton mFloatingActionButton;

    @Nullable
    @BindView(R.id.stations)
    RecyclerView mRecyclerView;

    @Nullable
    @BindView(R.id.divider)
    View mDivider;

    @BindView(R.id.swipe)
    SwipeRefreshLayout mSwipeRefreshLayout;

    @BindView(R.id.tabDots)
    TabLayout mTabLayout;

    // Activity lifecycle
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        GDPR.consent(this, true);
        Preference.initialize(this);
        if (Preference.isCrashlyticsEnabled(this)) {
            Fabric.with(this, new Crashlytics());
        }
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        if (!Preference.getLicense(this)) {
            License.showLicense(this);
        }

        if (getIntent().getBooleanExtra(PARAM_EXIT, false)) {
            finish();
        }

        setSupportActionBar(mToolbar);

        mSelectedStation = getIntent().getBundleExtra(PARAM_STATION);
        if (mSelectedStation == null)
            mSelectedStation = DatabaseService.selectedStation(this);
        else DatabaseService.selectStation(this, mSelectedStation);

        getSupportLoaderManager().initLoader(0, mSelectedStation, this);
        mTwoPane = getResources().getBoolean(R.bool.two_pane);
        if (mTwoPane) {
            getSupportLoaderManager().initLoader(1, mSelectedStation, this);
            if (mRecyclerView != null) {
                mStationListAdapter = new StationListAdapter();
                mRecyclerView.setAdapter(mStationListAdapter);
                mRecyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
                    @Override
                    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                               @NonNull RecyclerView parent,
                                               @NonNull RecyclerView.State state) {
                        super.getItemOffsets(outRect, view, parent, state);
                        int itemCount = state.getItemCount();

                        final int itemPosition = parent.getChildLayoutPosition(view);

                        if (itemPosition == RecyclerView.NO_POSITION) {
                            return;
                        }

                        if (itemCount > 0) {
                            if (itemPosition == itemCount - 1) {
                                outRect.set(0, 0,
                                        getResources().getDimensionPixelSize(R.dimen.edge),
                                        getResources().getDimensionPixelSize(R.dimen.edge));
                            } else {
                                outRect.set(0, 0,
                                        getResources().getDimensionPixelSize(R.dimen.edge),
                                        0);
                            }
                        }
                    }
                });
                mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(
                        this);

                mLocationCallback = new LocationCallbackReference(new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult locationResult) {
                        super.onLocationResult(locationResult);
                        if (locationResult == null) {
                            return;
                        }
                        for (Location location : locationResult.getLocations()) {
                            mStationListAdapter.setLocation(location);
                        }
                    }

                    @Override
                    public void onLocationAvailability(LocationAvailability locationAvailability) {
                        super.onLocationAvailability(locationAvailability);
                        mStationListAdapter.setDistance(locationAvailability.isLocationAvailable());
                    }
                });
                mLocationRequest = io.github.hazyair.util.Location.createLocationRequest();
            }
        }
        if (mViewPager != null) {

            mStationPagerAdapter = new StationPagerAdapter(getSupportFragmentManager());
            mViewPager.setAdapter(mStationPagerAdapter);
            if (mTwoPane) {
                mViewPager.setSwipeEnable(false);
            } else {
                mViewPager.setOffscreenPageLimit(8);
                mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                    @Override
                    public void onPageScrolled(int position, float positionOffset,
                                               int positionOffsetPixels) {

                    }

                    @Override
                    public void onPageSelected(int position) {
                        Cursor cursor = mStationPagerAdapter.getCursor();
                        cursor.moveToPosition(position);
                        mSelectedStation = Station.toBundleFromCursor(cursor);
                        DatabaseService.selectStation(MainActivity.this, mSelectedStation);
                    }

                    @Override
                    public void onPageScrollStateChanged(int state) {
                    }
                });
                mTabLayout.setupWithViewPager(mViewPager, true);
                mTabLayout.setVisibility(View.VISIBLE);
            }
        }

    }

    @Override
    protected void onDestroy() {
        if (mTwoPane) {
            mStationListAdapter = null;
        }
        if (mViewPager != null) mViewPager.setAdapter(null);
        mStationPagerAdapter = null;
        mViewPager = null;
        mSelectedStation = null;
        mTabLayout = null;
        mSwipeRefreshLayout = null;
        mRecyclerView = null;
        mDivider = null;
        mMenu = null;
        mToolbar = null;
        mFloatingActionButton = null;
        mLocationCallback = null;
        mLocationRequest = null;
        mFusedLocationProviderClient = null;
        super.onDestroy();
    }

    private void addRemoveStationButton() {
        if (mMenu == null || mMenu.findItem(ACTION_REMOVE_STATION) != null) return;
        mMenu.add(Menu.NONE, ACTION_REMOVE_STATION, Menu.NONE,
                getString(R.string.title_remove_station))
                .setIcon(R.drawable.ic_remove_circle_outline_white_24dp)
                .setShowAsAction(SHOW_AS_ACTION_IF_ROOM);
    }

    private void removeRemoveStationButton() {
        if (mMenu == null) return;
        mMenu.removeItem(ACTION_REMOVE_STATION);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mMenu = menu;
        getMenuInflater().inflate(R.menu.menu_main, menu);
        if (mTwoPane) {
            if (mStationListAdapter != null && mStationListAdapter.getItemCount() > 0) {
                addRemoveStationButton();
            }
        } else {
            if (mStationPagerAdapter != null && mStationPagerAdapter.getCount() > 0) {
                addRemoveStationButton();
            }
        }
        return super.onCreateOptionsMenu(menu);
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.action_license:
                License.showLicense(this);
                return true;
            case R.id.action_privacy:
                GDPR.consent(this);
                return true;
            case ACTION_REMOVE_STATION:
                int position;
                Cursor cursor;
                if (mTwoPane) {
                    if (mStationListAdapter == null) return true;
                    cursor = mStationListAdapter.getCursor();
                    if (cursor == null || cursor.getCount() == 0) return true;
                    removeRemoveStationButton();
                    mStationListAdapter.deselectCurrentStation();
                }
                if (mViewPager == null || mStationPagerAdapter == null) return true;
                cursor = mStationPagerAdapter.getCursor();
                if (cursor == null || cursor.getCount() == 0) return true;
                position = mViewPager.getCurrentItem();
                int count = mStationPagerAdapter.getCount();
                if (count > 0) {
                    if (position + 1 < count) {
                        cursor.moveToPosition(position + 1);
                    } else if (position > 0) {
                        cursor.moveToPosition(position - 1);
                    } else cursor.moveToFirst();
                    mSelectedStation = Station.toBundleFromCursor(cursor);
                } else {
                    mSelectedStation = null;
                }
                mStationPagerAdapter.removePage(position);
                DatabaseService.selectStation(MainActivity.this, mSelectedStation);
                cursor.moveToPosition(position);
                DatabaseService.delete(this, Station.toBundleFromCursor(cursor)
                        .getInt(StationsContract.COLUMN__ID));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DatabaseService.ACTION_UPDATED);
        intentFilter.addAction(DatabaseService.ACTION_UPDATING);
        registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onStop() {
        super.onStop();
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mFloatingActionButton.setOnClickListener((view) -> {
            mFloatingActionButton.setOnClickListener(null);
            startActivity(new Intent(MainActivity.this, StationsActivity.class));
        });
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            if (Network.isAvailable(MainActivity.this)) {
                if (!DatabaseService.update(this, TimeUnit.MINUTES.toMillis(30))) {
                    mSwipeRefreshLayout.setRefreshing(false);
                }
            } else {
                mSwipeRefreshLayout.setRefreshing(false);
                Network.showWarning(MainActivity.this);
            }
        });
        DatabaseSyncService.schedule(this);
        NotificationService.schedule(this);
        if (mTwoPane) requestUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mTwoPane) {
            io.github.hazyair.util.Location.removeUpdates(this,
                    mFusedLocationProviderClient, mLocationCallback);
        }
        mSwipeRefreshLayout.setRefreshing(false);
    }

    private void requestUpdates() {
        io.github.hazyair.util.Location.requestUpdates(this,
                mFusedLocationProviderClient, mLocationRequest, mLocationCallback);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        //noinspection SwitchStatementWithTooFewBranches
        switch (requestCode) {
            case PERMISSION_REQUEST_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    if (mTwoPane) {
                        requestUpdates();
                    } else {
                        for (Fragment fragment: getSupportFragmentManager().getFragments()) {
                            ((StationFragment) fragment).requestUpdates();
                        }
                    }

                }
            }

        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onNewIntent(Intent intent) {
        if (intent.getBooleanExtra(PARAM_EXIT, false)) {
            finish();
        }
        mSelectedStation = intent.getBundleExtra(PARAM_STATION);
        if (mSelectedStation == null)
            mSelectedStation = DatabaseService.selectedStation(this);
        else DatabaseService.selectStation(this, mSelectedStation);
        getSupportLoaderManager().restartLoader(0, mSelectedStation, this);
        if (mTwoPane)
            getSupportLoaderManager().restartLoader(1, mSelectedStation, this);
        super.onNewIntent(intent);
    }

    // Loader handlers
    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
        return StationsLoader.newInstanceForAllStations(MainActivity.this);
    }

    private void selectStation(int position) {
        if (mTwoPane) {
            if (mStationListAdapter != null) mStationListAdapter.setCurrentItem(position);
            if (mRecyclerView != null) mRecyclerView.scrollToPosition(position);
        }
        if (mViewPager != null) mViewPager.setCurrentItem(position, false);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor data) {
        if (data == null) return;
        int count = data.getCount();
        switch (loader.getId()) {
            case 0: {
                mStationPagerAdapter.setCursor(data);
                if (count > 0)
                    io.github.hazyair.util.Location.checkPermission(this);
                if (count == 0) {
                    removeRemoveStationButton();
                    DatabaseService.selectStation(this, null);
                } else {
                    addRemoveStationButton();
                    if (mSelectedStation != null) {
                        int i;
                        for (i = 0; i < count; i++) {
                            data.moveToPosition(i);
                            if (Station.equals(Station.toBundleFromCursor(data),
                                    mSelectedStation)) {
                                selectStation(i);
                                break;
                            }
                        }
                        if (i == count) mSelectedStation = null;
                    }
                    if (mSelectedStation == null) {
                        data.moveToFirst();
                        DatabaseService.selectStation(this,
                                Station.toBundleFromCursor(data));
                        selectStation(0);
                    }
                }
                break;
            }
            case 1: {
                if (mTwoPane) {
                    if (count == 0) {
                        if (mDivider != null) mDivider.setVisibility(View.GONE);
                    } else {
                        if (mDivider != null) mDivider.setVisibility(View.VISIBLE);
                    }
                    mStationListAdapter.setCursor(data);
                }
                break;
            }
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {
        if (mTwoPane) {
            mStationListAdapter.setCursor(null);
        } else {
            mStationPagerAdapter.setCursor(null);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (mTwoPane && mStationListAdapter != null) mStationListAdapter.setLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {
        if (mTwoPane && mStationListAdapter != null) mStationListAdapter.setDistance(true);
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (mTwoPane && mStationListAdapter != null) mStationListAdapter.setDistance(false);
    }

}

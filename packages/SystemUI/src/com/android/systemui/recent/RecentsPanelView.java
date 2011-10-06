/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recent;

import java.util.ArrayList;
import java.util.List;

import android.animation.Animator;
import android.animation.LayoutTransition;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView.ScaleType;

import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBar;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.tablet.StatusBarPanel;
import com.android.systemui.statusbar.tablet.TabletStatusBar;

public class RecentsPanelView extends RelativeLayout
        implements OnItemClickListener, RecentsCallback, StatusBarPanel, Animator.AnimatorListener {
    static final String TAG = "RecentsPanelView";
    static final boolean DEBUG = TabletStatusBar.DEBUG || PhoneStatusBar.DEBUG || false;
    private static final int DISPLAY_TASKS = 20;
    private static final int MAX_TASKS = DISPLAY_TASKS + 1; // allow extra for non-apps
    private StatusBar mBar;
    private ArrayList<ActivityDescription> mActivityDescriptions;
    private AsyncTask<Void, Integer, Void> mThumbnailLoader;
    private int mIconDpi;
    private View mRecentsScrim;
    private View mRecentsGlowView;
    private View mRecentsNoApps;
    private ViewGroup mRecentsContainer;
    private Bitmap mDefaultThumbnailBackground;

    private boolean mShowing;
    private Choreographer mChoreo;
    private View mRecentsDismissButton;
    private ActivityDescriptionAdapter mListAdapter;
    private final Handler mHandler = new Handler();

    /* package */ final class ActivityDescription {
        final ActivityManager.RecentTaskInfo recentTaskInfo;
        final ResolveInfo resolveInfo;
        final int taskId; // application task id for curating apps
        final int persistentTaskId; // persistent id
        final Intent intent; // launch intent for application
        final String packageName; // used to override animations (see onClick())
        final int position; // position in list

        Matrix matrix; // arbitrary rotation matrix to correct orientation

        private Bitmap mThumbnail; // generated by Activity.onCreateThumbnail()
        private Drawable mIcon; // application package icon
        private CharSequence mLabel; // application package label

        public ActivityDescription(ActivityManager.RecentTaskInfo _recentInfo,
                ResolveInfo _resolveInfo, Intent _intent,
                int _pos, String _packageName) {
            recentTaskInfo = _recentInfo;
            resolveInfo = _resolveInfo;
            intent = _intent;
            taskId = _recentInfo.id;
            persistentTaskId = _recentInfo.persistentId;
            position = _pos;
            packageName = _packageName;
        }

        public CharSequence getLabel() {
            return mLabel;
        }

        public Drawable getIcon() {
            return mIcon;
        }

        public void setThumbnail(Bitmap thumbnail) {
            mThumbnail = thumbnail;
        }

        public Bitmap getThumbnail() {
            return mThumbnail;
        }
    }

    private final class OnLongClickDelegate implements View.OnLongClickListener {
        View mOtherView;
        OnLongClickDelegate(View other) {
            mOtherView = other;
        }
        public boolean onLongClick(View v) {
            return mOtherView.performLongClick();
        }
    }

    /* package */ final static class ViewHolder {
        View thumbnailView;
        ImageView thumbnailViewImage;
        ImageView iconView;
        TextView labelView;
        TextView descriptionView;
        ActivityDescription activityDescription;
    }

    /* package */ final class ActivityDescriptionAdapter extends BaseAdapter {
        private LayoutInflater mInflater;

        public ActivityDescriptionAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
        }

        public int getCount() {
            return mActivityDescriptions != null ? mActivityDescriptions.size() : 0;
        }

        public Object getItem(int position) {
            return position; // we only need the index
        }

        public long getItemId(int position) {
            return position; // we just need something unique for this position
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.status_bar_recent_item, parent, false);
                holder = new ViewHolder();
                holder.thumbnailView = convertView.findViewById(R.id.app_thumbnail);
                holder.thumbnailViewImage = (ImageView) convertView.findViewById(
                        R.id.app_thumbnail_image);
                holder.iconView = (ImageView) convertView.findViewById(R.id.app_icon);
                holder.labelView = (TextView) convertView.findViewById(R.id.app_label);
                holder.descriptionView = (TextView) convertView.findViewById(R.id.app_description);

                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // activityId is reverse since most recent appears at the bottom...
            final int activityId = mActivityDescriptions.size() - position - 1;

            final ActivityDescription activityDescription = mActivityDescriptions.get(activityId);
            holder.thumbnailViewImage.setImageBitmap(activityDescription.getThumbnail());
            holder.iconView.setImageDrawable(activityDescription.getIcon());
            holder.labelView.setText(activityDescription.getLabel());
            holder.descriptionView.setText(activityDescription.recentTaskInfo.description);
            holder.thumbnailView.setTag(activityDescription);
            holder.thumbnailView.setOnLongClickListener(new OnLongClickDelegate(convertView));
            holder.thumbnailView.setContentDescription(activityDescription.getLabel());
            holder.activityDescription = activityDescription;

            return convertView;
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !event.isCanceled()) {
            show(false, true);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    public boolean isInContentArea(int x, int y) {
        // use mRecentsContainer's exact bounds to determine horizontal position
        final int l = mRecentsContainer.getLeft();
        final int r = mRecentsContainer.getRight();
        // use surrounding mRecentsGlowView's position in parent determine vertical bounds
        final int t = mRecentsGlowView.getTop();
        final int b = mRecentsGlowView.getBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public void show(boolean show, boolean animate) {
        if (show) {
            // Need to update list of recent apps before we set visibility so this view's
            // content description is updated before it gets focus for TalkBack mode
            refreshApplicationList();

            // if there are no apps, either bring up a "No recent apps" message, or just
            // quit early
            boolean noApps = (mActivityDescriptions.size() == 0);
            if (mRecentsNoApps != null) { // doesn't exist on large devices
                mRecentsNoApps.setVisibility(noApps ? View.VISIBLE : View.INVISIBLE);
            } else {
                if (noApps) {
                    if (DEBUG) Log.v(TAG, "Nothing to show");
                    return;
                }
            }
        }
        if (animate) {
            if (mShowing != show) {
                mShowing = show;
                if (show) {
                    setVisibility(View.VISIBLE);
                }
                mChoreo.startAnimation(show);
            }
        } else {
            mShowing = show;
            setVisibility(show ? View.VISIBLE : View.GONE);
            mChoreo.jumpTo(show);
        }
        if (show) {
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        }
    }

    public void dismiss() {
        hide(true);
    }

    public void hide(boolean animate) {
        if (!animate) {
            setVisibility(View.GONE);
        }
        if (mBar != null) {
            mBar.animateCollapse();
        }
    }

    public void handleShowBackground(boolean show) {
        if (show) {
            mRecentsScrim.setBackgroundResource(R.drawable.status_bar_recents_background);
        } else {
            mRecentsScrim.setBackgroundDrawable(null);
        }
    }

    public boolean isRecentsVisible() {
        return getVisibility() == VISIBLE;
    }

    public void onAnimationCancel(Animator animation) {
    }

    public void onAnimationEnd(Animator animation) {
        if (mShowing) {
            final LayoutTransition transitioner = new LayoutTransition();
            ((ViewGroup)mRecentsContainer).setLayoutTransition(transitioner);
            createCustomAnimations(transitioner);
        } else {
            ((ViewGroup)mRecentsContainer).setLayoutTransition(null);
        }
    }

    public void onAnimationRepeat(Animator animation) {
    }

    public void onAnimationStart(Animator animation) {
    }


    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mChoreo.setPanelHeight(mRecentsContainer.getHeight());
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return mShowing;
    }

    public void setBar(StatusBar bar) {
        mBar = bar;
    }

    public RecentsPanelView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsPanelView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        Resources res = context.getResources();
        boolean xlarge = (res.getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE;

        mIconDpi = xlarge ? DisplayMetrics.DENSITY_HIGH : res.getDisplayMetrics().densityDpi;

        int width = (int) res.getDimension(R.dimen.status_bar_recents_thumbnail_width);
        int height = (int) res.getDimension(R.dimen.status_bar_recents_thumbnail_height);
        int color = res.getColor(R.drawable.status_bar_recents_app_thumbnail_background);

        // Render the default thumbnail background
        mDefaultThumbnailBackground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(mDefaultThumbnailBackground);
        c.drawColor(color);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mRecentsContainer = (ViewGroup) findViewById(R.id.recents_container);
        mListAdapter = new ActivityDescriptionAdapter(mContext);
        if (mRecentsContainer instanceof RecentsHorizontalScrollView){
            RecentsHorizontalScrollView scrollView
                    = (RecentsHorizontalScrollView) mRecentsContainer;
            scrollView.setAdapter(mListAdapter);
            scrollView.setCallback(this);
        } else if (mRecentsContainer instanceof RecentsVerticalScrollView){
            RecentsVerticalScrollView scrollView
                    = (RecentsVerticalScrollView) mRecentsContainer;
            scrollView.setAdapter(mListAdapter);
            scrollView.setCallback(this);
        }
        else {
            throw new IllegalArgumentException("missing Recents[Horizontal]ScrollView");
        }


        mRecentsGlowView = findViewById(R.id.recents_glow);
        mRecentsScrim = findViewById(R.id.recents_bg_protect);
        mRecentsNoApps = findViewById(R.id.recents_no_apps);
        mChoreo = new Choreographer(this, mRecentsScrim, mRecentsGlowView, mRecentsNoApps, this);
        mRecentsDismissButton = findViewById(R.id.recents_dismiss_button);
        if (mRecentsDismissButton != null) {
            mRecentsDismissButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    hide(true);
                }
            });
        }

        // In order to save space, we make the background texture repeat in the Y direction
        if (mRecentsScrim != null && mRecentsScrim.getBackground() instanceof BitmapDrawable) {
            ((BitmapDrawable) mRecentsScrim.getBackground()).setTileModeY(TileMode.REPEAT);
        }
    }

    private void createCustomAnimations(LayoutTransition transitioner) {
        transitioner.setDuration(200);
        transitioner.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transitioner.setAnimator(LayoutTransition.DISAPPEARING, null);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (DEBUG) Log.v(TAG, "onVisibilityChanged(" + changedView + ", " + visibility + ")");

        if (mRecentsContainer instanceof RecentsHorizontalScrollView) {
            ((RecentsHorizontalScrollView) mRecentsContainer).onRecentsVisibilityChanged();
        } else if (mRecentsContainer instanceof RecentsVerticalScrollView) {
            ((RecentsVerticalScrollView) mRecentsContainer).onRecentsVisibilityChanged();
        } else {
            throw new IllegalArgumentException("missing Recents[Horizontal]ScrollView");
        }
    }

    Drawable getFullResDefaultActivityIcon() {
        return getFullResIcon(Resources.getSystem(),
                com.android.internal.R.mipmap.sym_def_app_icon);
    }

    Drawable getFullResIcon(Resources resources, int iconId) {
        try {
            return resources.getDrawableForDensity(iconId, mIconDpi);
        } catch (Resources.NotFoundException e) {
            return getFullResDefaultActivityIcon();
        }
    }

    private Drawable getFullResIcon(ResolveInfo info, PackageManager packageManager) {
        Resources resources;
        try {
            resources = packageManager.getResourcesForApplication(
                    info.activityInfo.applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            resources = null;
        }
        if (resources != null) {
            int iconId = info.activityInfo.getIconResource();
            if (iconId != 0) {
                return getFullResIcon(resources, iconId);
            }
        }
        return getFullResDefaultActivityIcon();
    }

    private ArrayList<ActivityDescription> getRecentTasks() {
        ArrayList<ActivityDescription> activityDescriptions = new ArrayList<ActivityDescription>();
        final PackageManager pm = mContext.getPackageManager();
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);

        final List<ActivityManager.RecentTaskInfo> recentTasks =
                am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

        ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .resolveActivityInfo(pm, 0);

        int numTasks = recentTasks.size();

        // skip the first activity - assume it's either the home screen or the current app.
        final int first = 1;
        for (int i = first, index = 0; i < numTasks && (index < MAX_TASKS); ++i) {
            final ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);

            Intent intent = new Intent(recentInfo.baseIntent);
            if (recentInfo.origActivity != null) {
                intent.setComponent(recentInfo.origActivity);
            }

            // Skip the current home activity.
            if (homeInfo != null
                    && homeInfo.packageName.equals(intent.getComponent().getPackageName())
                    && homeInfo.name.equals(intent.getComponent().getClassName())) {
                continue;
            }

            intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
            if (resolveInfo != null) {
                final ActivityInfo info = resolveInfo.activityInfo;
                final String title = info.loadLabel(pm).toString();
                Drawable icon = getFullResIcon(resolveInfo, pm);
                if (title != null && title.length() > 0 && icon != null) {
                    if (DEBUG) Log.v(TAG, "creating activity desc for id="
                            + recentInfo.id + ", label=" + title);
                    ActivityDescription item = new ActivityDescription(recentInfo,
                            resolveInfo, intent, index, info.packageName);
                    activityDescriptions.add(item);
                    ++index;
                } else {
                    if (DEBUG) Log.v(TAG, "SKIPPING item " + recentInfo.id);
                }
            }
        }
        return activityDescriptions;
    }

    ActivityDescription findActivityDescription(int id)
    {
        ActivityDescription desc = null;
        for (int i = 0; i < mActivityDescriptions.size(); i++) {
            ActivityDescription item = mActivityDescriptions.get(i);
            if (item != null && item.taskId == id) {
                desc = item;
                break;
            }
        }
        return desc;
    }

    void loadActivityDescription(ActivityDescription ad, int index) {
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        final PackageManager pm = mContext.getPackageManager();
        ActivityManager.TaskThumbnails thumbs = am.getTaskThumbnails(
                ad.recentTaskInfo.persistentId);
        CharSequence label = ad.resolveInfo.activityInfo.loadLabel(pm);
        Drawable icon = getFullResIcon(ad.resolveInfo, pm);
        if (DEBUG) Log.v(TAG, "Loaded bitmap for #" + index + " in "
                + ad + ": " + thumbs.mainThumbnail);
        synchronized (ad) {
            ad.mLabel = label;
            ad.mIcon = icon;
            if (thumbs != null && thumbs.mainThumbnail != null) {
                ad.setThumbnail(thumbs.mainThumbnail);
            }
        }
    }

    void applyActivityDescription(ActivityDescription ad, int index, boolean anim) {
        synchronized (ad) {
            if (mRecentsContainer != null) {
                ViewGroup container = mRecentsContainer;
                if (container instanceof HorizontalScrollView
                        || container instanceof ScrollView) {
                    container = (ViewGroup)container.findViewById(
                            R.id.recents_linear_layout);
                }
                // Look for a view showing this thumbnail, to update.
                for (int i=0; i<container.getChildCount(); i++) {
                    View v = container.getChildAt(i);
                    if (v.getTag() instanceof ViewHolder) {
                        ViewHolder h = (ViewHolder)v.getTag();
                        if (h.activityDescription == ad) {
                            if (DEBUG) Log.v(TAG, "Updating thumbnail #" + index + " in "
                                    + h.activityDescription
                                    + ": " + ad.getThumbnail());
                            h.iconView.setImageDrawable(ad.getIcon());
                            if (anim) {
                                h.iconView.setAnimation(AnimationUtils.loadAnimation(
                                        mContext, R.anim.recent_appear));
                            }
                            h.iconView.setVisibility(View.VISIBLE);
                            h.labelView.setText(ad.getLabel());
                            h.thumbnailView.setContentDescription(ad.getLabel());
                            if (anim) {
                                h.labelView.setAnimation(AnimationUtils.loadAnimation(
                                        mContext, R.anim.recent_appear));
                            }
                            h.labelView.setVisibility(View.VISIBLE);
                            Bitmap thumbnail = ad.getThumbnail();
                            if (thumbnail != null) {
                                // Should remove the default image in the frame
                                // that this now covers, to improve scrolling speed.
                                // That can't be done until the anim is complete though.
                                h.thumbnailViewImage.setImageBitmap(thumbnail);

                                // scale to fill up the full width
                                Matrix scaleMatrix = new Matrix();
                                float thumbnailViewWidth = h.thumbnailViewImage.getWidth();
                                float scale = thumbnailViewWidth / thumbnail.getWidth();
                                scaleMatrix.setScale(scale, scale);
                                h.thumbnailViewImage.setScaleType(ScaleType.MATRIX);
                                h.thumbnailViewImage.setImageMatrix(scaleMatrix);

                                if (anim) {
                                    h.thumbnailViewImage.setAnimation(AnimationUtils.loadAnimation(
                                            mContext, R.anim.recent_appear));
                                }
                                h.thumbnailViewImage.setVisibility(View.VISIBLE);
                            }
                        }
                    }
                }
            }
        }
    }

    private void refreshApplicationList() {
        if (mThumbnailLoader != null) {
            mThumbnailLoader.cancel(false);
            mThumbnailLoader = null;
        }

        mActivityDescriptions = getRecentTasks();
        int numRecentApps = mActivityDescriptions.size();
        String recentAppsAccessibilityDescription;
        if (numRecentApps == 0) {
            recentAppsAccessibilityDescription =
                getResources().getString(R.string.status_bar_no_recent_apps);
        } else {
            recentAppsAccessibilityDescription = getResources().getQuantityString(
                R.plurals.status_bar_accessibility_recent_apps, numRecentApps, numRecentApps);
        }
        setContentDescription(recentAppsAccessibilityDescription);
        for (ActivityDescription ad : mActivityDescriptions) {
            ad.setThumbnail(mDefaultThumbnailBackground);
        }
        mListAdapter.notifyDataSetInvalidated();
        if (mActivityDescriptions.size() > 0) {
            if (DEBUG) Log.v(TAG, "Showing " + mActivityDescriptions.size() + " apps");
            updateUiElements(getResources().getConfiguration());
            final ArrayList<ActivityDescription> descriptions =
                new ArrayList<ActivityDescription>(mActivityDescriptions);
            loadActivityDescription(descriptions.get(0), 0);
            applyActivityDescription(descriptions.get(0), 0, false);
            if (descriptions.size() > 1) {
                mThumbnailLoader = new AsyncTask<Void, Integer, Void>() {
                    @Override
                    protected void onProgressUpdate(Integer... values) {
                        final ActivityDescription ad = descriptions.get(values[0]);
                        if (!isCancelled()) {
                            applyActivityDescription(ad, values[0], true);
                        }
                        // This is to prevent the loader thread from getting ahead
                        // of our UI updates.
                        mHandler.post(new Runnable() {
                            @Override public void run() {
                                synchronized (ad) {
                                    ad.notifyAll();
                                }
                            }
                        });
                    }

                    @Override
                    protected Void doInBackground(Void... params) {
                        final int origPri = Process.getThreadPriority(Process.myTid());
                        Process.setThreadPriority(Process.THREAD_GROUP_BG_NONINTERACTIVE);
                        long nextTime = SystemClock.uptimeMillis();
                        for (int i=1; i<descriptions.size(); i++) {
                            ActivityDescription ad = descriptions.get(i);
                            loadActivityDescription(ad, i);
                            long now = SystemClock.uptimeMillis();
                            nextTime += 150;
                            if (nextTime > now) {
                                try {
                                    Thread.sleep(nextTime-now);
                                } catch (InterruptedException e) {
                                }
                            }
                            if (isCancelled()) {
                                break;
                            }
                            synchronized (ad) {
                                publishProgress(i);
                                try {
                                    ad.wait(500);
                                } catch (InterruptedException e) {
                                }
                            }
                        }
                        Process.setThreadPriority(origPri);
                        return null;
                    }
                };
                mThumbnailLoader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        }
    }

    private void updateUiElements(Configuration config) {
        final int items = mActivityDescriptions.size();

        mRecentsContainer.setVisibility(items > 0 ? View.VISIBLE : View.GONE);
        mRecentsGlowView.setVisibility(items > 0 ? View.VISIBLE : View.GONE);
    }

    public void handleOnClick(View view) {
        ActivityDescription ad = ((ViewHolder) view.getTag()).activityDescription;
        final Context context = view.getContext();
        final ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        if (ad.taskId >= 0) {
            // This is an active task; it should just go to the foreground.
            am.moveTaskToFront(ad.taskId, ActivityManager.MOVE_TASK_WITH_HOME);
        } else {
            Intent intent = ad.intent;
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                    | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
            if (DEBUG) Log.v(TAG, "Starting activity " + intent);
            context.startActivity(intent);
        }
        hide(true);
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        handleOnClick(view);
    }

    public void handleSwipe(View view) {
        ActivityDescription ad = ((ViewHolder) view.getTag()).activityDescription;
        if (DEBUG) Log.v(TAG, "Jettison " + ad.getLabel());
        mActivityDescriptions.remove(ad);

        // Handled by widget containers to enable LayoutTransitions properly
        // mListAdapter.notifyDataSetChanged();

        if (mActivityDescriptions.size() == 0) {
            hide(false);
        }

        // Currently, either direction means the same thing, so ignore direction and remove
        // the task.
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        am.removeTask(ad.persistentTaskId, ActivityManager.REMOVE_TASK_KILL_PROCESS);
    }

    private void startApplicationDetailsActivity(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
    }

    public void handleLongPress(
            final View selectedView, final View anchorView, final View thumbnailView) {
        thumbnailView.setSelected(true);
        PopupMenu popup = new PopupMenu(mContext, anchorView == null ? selectedView : anchorView);
        popup.getMenuInflater().inflate(R.menu.recent_popup_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.recent_remove_item) {
                    mRecentsContainer.removeViewInLayout(selectedView);
                } else if (item.getItemId() == R.id.recent_inspect_item) {
                    ViewHolder viewHolder = (ViewHolder) selectedView.getTag();
                    if (viewHolder != null) {
                        final ActivityDescription ad = viewHolder.activityDescription;
                        startApplicationDetailsActivity(ad.packageName);
                        mBar.animateCollapse();
                    } else {
                        throw new IllegalStateException("Oops, no tag on view " + selectedView);
                    }
                } else {
                    return false;
                }
                return true;
            }
        });
        popup.setOnDismissListener(new PopupMenu.OnDismissListener() {
            public void onDismiss(PopupMenu menu) {
                thumbnailView.setSelected(false);
            }
        });
        popup.show();
    }
}
// Copyright 2011 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package xyz.aprildown.chromemenu;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnKeyListener;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.IdRes;

/**
 * Shows a popup of menuitems anchored to a host view. When a item is selected we call
 * AppMenuPropertiesDelegate.onMenuItemClicked with the appropriate MenuItem.
 * - Only visible MenuItems are shown.
 * - Disabled items are grayed out.
 */
public class AppMenu implements OnItemClickListener, OnKeyListener {

    private static final float LAST_ITEM_SHOW_FRACTION = 0.5f;

    private final Menu mMenu;
    private final int mItemRowHeight;
    private final int mItemDividerHeight;
    private final int mVerticalFadeDistance;
    private final int mNegativeSoftwareVerticalOffset;
    private final int mNegativeVerticalOffsetNotTopAnchored;
    private final int[] mTempLocation;

    private PopupWindow mPopup;
    private ListView mListView;
    private AppMenuAdapter mAdapter;
    private AppMenuHandler mHandler;
    private boolean mIsByPermanentButton;
    private AnimatorSet mMenuItemEnterAnimator;
    /*private AnimatorListener mAnimationHistogramRecorder = AnimationFrameTimeHistogram
            .getAnimatorRecorder("WrenchMenu.OpeningAnimationFrameTimes");*/

    /**
     * Creates and sets up the App Menu.
     *
     * @param menu              Original menu created by the framework.
     * @param itemRowHeight     Desired height for each app menu row.
     * @param itemDividerHeight Desired height for the divider between app menu items.
     * @param handler           AppMenuHandler receives callbacks from AppMenu.
     * @param res               Resources object used to get dimensions and style attributes.
     */
    AppMenu(Menu menu, int itemRowHeight, int itemDividerHeight, AppMenuHandler handler,
            Resources res) {
        mMenu = menu;

        mItemRowHeight = itemRowHeight;
        if (mItemRowHeight <= 0) {
            throw new IllegalArgumentException("ItemRowHeight must be positive");
        }

        mHandler = handler;

        mItemDividerHeight = itemDividerHeight;
        if (mItemDividerHeight < 0) {
            throw new IllegalArgumentException("ItemDividerHeight must be positive");
        }

        mNegativeSoftwareVerticalOffset =
                res.getDimensionPixelSize(R.dimen.menu_negative_software_vertical_offset);
        mVerticalFadeDistance = res.getDimensionPixelSize(R.dimen.menu_vertical_fade_distance);
        mNegativeVerticalOffsetNotTopAnchored =
                res.getDimensionPixelSize(R.dimen.menu_negative_vertical_offset_not_top_anchored);

        mTempLocation = new int[2];
    }

    /**
     * Shows the content description toast for items on the toolbar.
     *
     * @param context     The context to use for the toast.
     * @param view        The view to anchor the toast.
     * @param description The string shown in the toast.
     * @return Whether a toast has been shown successfully.
     */
    @SuppressLint("RtlHardcoded")
    private static boolean showAccessibilityToast(
            Context context, View view, CharSequence description) {
        if (description == null) return false;

        final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        final int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        final int[] screenPos = new int[2];
        view.getLocationOnScreen(screenPos);
        final int width = view.getWidth();
        final int height = view.getHeight();

        final int horizontalGravity =
                (screenPos[0] < screenWidth / 2) ? Gravity.LEFT : Gravity.RIGHT;
        final int xOffset = (screenPos[0] < screenWidth / 2)
                ? screenPos[0] + width / 2
                : screenWidth - screenPos[0] - width / 2;
        final int yOffset = (screenPos[1] < screenHeight / 2) ? screenPos[1] + height / 2
                : screenPos[1] - height * 3 / 2;

        Toast toast = Toast.makeText(context, description, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | horizontalGravity, xOffset, yOffset);
        toast.show();
        return true;
    }

    /**
     * Notifies the menu that the contents of the menu item specified by {@code menuRowId} have
     * changed.  This should be called if icons, titles, etc. are changing for a particular menu
     * item while the menu is open.
     *
     * @param menuRowId The id of the menu item to change.  This must be a row id and not a child
     *                  id.
     */
    void menuItemContentChanged(int menuRowId) {
        // Make sure we have all the valid state objects we need.
        if (mAdapter == null || mMenu == null || mPopup == null || mListView == null) {
            return;
        }

        // Calculate the item index.
        int index = -1;
        int menuSize = mMenu.size();
        for (int i = 0; i < menuSize; i++) {
            if (mMenu.getItem(i).getItemId() == menuRowId) {
                index = i;
                break;
            }
        }
        if (index == -1) return;

        // Check if the item is visible.
        int startIndex = mListView.getFirstVisiblePosition();
        int endIndex = mListView.getLastVisiblePosition();
        if (index < startIndex || index > endIndex) return;

        // Grab the correct View.
        View view = mListView.getChildAt(index - startIndex);
        if (view == null) return;

        // Cause the Adapter to re-populate the View.
        mListView.getAdapter().getView(index, view, mListView);
    }

    private int[] getPopupPosition(int screenRotation, Rect appRect, Rect padding, View anchorView,
                                   int popupWidth, int popupHeight, boolean isAnchorAtBottom) {
        anchorView.getLocationInWindow(mTempLocation);
        int anchorViewX = mTempLocation[0];
        int anchorViewY = mTempLocation[1];

        int[] offsets = new int[2];
        // If we have a hardware menu button, locate the app menu closer to the estimated
        // hardware menu button location.
        if (mIsByPermanentButton) {
            int horizontalOffset = -anchorViewX;
            switch (screenRotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    horizontalOffset += (appRect.width() - popupWidth) / 2;
                    break;
                case Surface.ROTATION_90:
                    horizontalOffset += appRect.width() - popupWidth;
                    break;
                case Surface.ROTATION_270:
                    break;
                default:
                    throw new IllegalStateException("Unknown screenRotation: " + screenRotation);
            }
            offsets[0] = horizontalOffset;
            // The menu is displayed above the anchored view, so shift the menu up by the bottom
            // padding of the background.
            offsets[1] = -padding.bottom;
        } else {
            offsets[1] = -mNegativeSoftwareVerticalOffset;

            // If the anchor is at the bottom of the screen, align the popup with the bottom of the
            // anchor. The anchor may not be fully visible, so
            // (appRect.bottom - anchorViewLocationOnScreenY) is used to determine the visible
            // bottom edge of the anchor view.
            if (isAnchorAtBottom) {
                anchorView.getLocationOnScreen(mTempLocation);
                int anchorViewLocationOnScreenY = mTempLocation[1];
                offsets[1] += appRect.bottom - anchorViewLocationOnScreenY - popupHeight;
                offsets[1] -= mNegativeVerticalOffsetNotTopAnchored;
                if (!mIsByPermanentButton) offsets[1] += padding.bottom;
            }

            if (!ApiCompatibilityUtils.isLayoutRtl(anchorView.getRootView())) {
                offsets[0] = anchorView.getWidth() - popupWidth;
            }
        }

        int xPos = anchorViewX + offsets[0];
        int yPos = anchorViewY + offsets[1];
        return new int[]{xPos, yPos};
    }

    /**
     * Handles clicks on the AppMenu popup.
     *
     * @param menuItem The menu item in the popup that was clicked.
     */
    void onItemClick(MenuItem menuItem) {
        if (menuItem.isEnabled()) {
            dismiss();
            mHandler.onOptionsItemSelected(menuItem);
        }
    }

    /**
     * Creates and shows the app menu anchored to the specified view.
     *  @param context             The context of the AppMenu (ensure the proper theme is set on
     *                            this context).
     * @param anchorView          The anchor {@link View} of the {@link PopupWindow}.
     * @param screenRotation      Current device screen rotation.
     * @param visibleDisplayFrame The display area rect in which AppMenu is supposed to fit in.
     * @param screenHeight        Current device screen height.
     * @param footerResourceId    The resource id for a view to add as a fixed view at the bottom
     *                            of the menu.  Can be 0 if no such view is required.  The footer
     *                            is always visible and overlays other app menu items if
     *                            necessary.
     * @param headerResourceId    The resource id for a view to add as the first item in menu
     *                            list. Can be null if no such view is required. See
     *                            {@link ListView#addHeaderView(View)}.
     * @param highlightedItemId   The resource id of the menu item that should be highlighted.
     *                            Can be {@code null} if no item should be highlighted.  Note that
     *                            {@code 0} is dedicated to custom menu items and can be declared
     *                            by external apps.
     * @param showFromBottom      Whether the appearance animation should run from the bottom up.
     */
    void show(Context context, final View anchorView,
              int screenRotation, Rect visibleDisplayFrame, int screenHeight,
              @IdRes int footerResourceId, @IdRes int headerResourceId, Integer highlightedItemId,
              boolean showFromBottom) {
        mPopup = new PopupWindow(context);
        mPopup.setFocusable(true);
        mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // The window layout type affects the z-index of the popup window on M+.
            mPopup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
        }

        mPopup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                if (anchorView instanceof ImageButton) {
                    anchorView.setSelected(false);
                }

                if (mMenuItemEnterAnimator != null) mMenuItemEnterAnimator.cancel();

                mHandler.appMenuDismissed();
                mHandler.onMenuVisibilityChanged(false);

                mPopup = null;
                mAdapter = null;
                mListView = null;
                mMenuItemEnterAnimator = null;
            }
        });

        // Some OEMs don't actually let us change the background... but they still return the
        // padding of the new background, which breaks the menu height.  If we still have a
        // drawable here even though our style says @null we should use this padding instead...
        Drawable originalBgDrawable = mPopup.getBackground();

        // Need to explicitly set the background here.  Relying on it being set in the style caused
        // an incorrectly drawn background.
        mPopup.setBackgroundDrawable(
                ApiCompatibilityUtils.getDrawable(context.getResources(), R.drawable.popup_bg));
        mPopup.setAnimationStyle(
                showFromBottom ? R.style.OverflowMenuAnimBottom : R.style.OverflowMenuAnim);

        // Turn off window animations for low end devices.
        if (SysUtils.isLowEndDevice()) mPopup.setAnimationStyle(0);

        Rect bgPadding = new Rect();
        mPopup.getBackground().getPadding(bgPadding);

        int menuWidth = context.getResources().getDimensionPixelSize(R.dimen.menu_width);
        int popupWidth = menuWidth + bgPadding.left + bgPadding.right;

        mPopup.setWidth(popupWidth);

        mIsByPermanentButton = false;

        // Extract visible items from the Menu.
        int numItems = mMenu.size();
        List<MenuItem> menuItems = new ArrayList<>();
        for (int i = 0; i < numItems; ++i) {
            MenuItem item = mMenu.getItem(i);
            if (item.isVisible()) {
                menuItems.add(item);
            }
        }

        Rect sizingPadding = new Rect(bgPadding);

        // A List adapter for visible items in the Menu. The first row is added as a header to the
        // list view.
        mAdapter = new AppMenuAdapter(
                this, menuItems, LayoutInflater.from(context), highlightedItemId);

        @SuppressLint("InflateParams") ViewGroup contentView =
                (ViewGroup) LayoutInflater.from(context).inflate(R.layout.app_menu_layout, null);
        mListView = contentView.findViewById(R.id.app_menu_list);

        int footerHeight =
                inflateFooter(footerResourceId, contentView, menuWidth, highlightedItemId);
        int headerHeight = inflateHeader(headerResourceId, contentView, menuWidth);

        // Set the adapter after the header is added to avoid crashes on JellyBean.
        // See crbug.com/761726.
        mListView.setAdapter(mAdapter);

        int popupHeight = setMenuHeight(menuItems.size(), visibleDisplayFrame, screenHeight,
                sizingPadding, footerHeight, headerHeight, anchorView);
        int[] popupPosition = getPopupPosition(screenRotation, visibleDisplayFrame,
                sizingPadding, anchorView, popupWidth, popupHeight, showFromBottom);

        mPopup.setContentView(contentView);
        mPopup.showAtLocation(
                anchorView.getRootView(), Gravity.NO_GRAVITY, popupPosition[0], popupPosition[1]);

        mListView.setOnItemClickListener(this);
        mListView.setItemsCanFocus(true);
        mListView.setOnKeyListener(this);

        mHandler.onMenuVisibilityChanged(true);

        if (mVerticalFadeDistance > 0) {
            mListView.setVerticalFadingEdgeEnabled(true);
            mListView.setFadingEdgeLength(mVerticalFadeDistance);
        }

        // Don't animate the menu items for low end devices.
        if (!SysUtils.isLowEndDevice()) {
            mListView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    mListView.removeOnLayoutChangeListener(this);
                    runMenuItemEnterAnimations();
                }
            });
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        onItemClick(mAdapter.getItem(position));
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (mListView == null) return false;

        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                event.startTracking();
                v.getKeyDispatcherState().startTracking(event, this);
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                v.getKeyDispatcherState().handleUpEvent(event);
                if (event.isTracking() && !event.isCanceled()) {
                    dismiss();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Dismisses the app menu and cancels the drag-to-scroll if it is taking place.
     */
    void dismiss() {
        if (isShowing()) {
            mPopup.dismiss();
        }
    }

    /**
     * @return Whether the app menu is currently showing.
     */
    boolean isShowing() {
        if (mPopup == null) {
            return false;
        }
        return mPopup.isShowing();
    }

    /**
     * @return {@link PopupWindow} that displays all the menu options and optional footer.
     */
    PopupWindow getPopup() {
        return mPopup;
    }

    /**
     * @return {@link ListView} that contains all of the menu options.
     */
    ListView getListView() {
        return mListView;
    }

    private int setMenuHeight(int numMenuItems, Rect appDimensions, int screenHeight, Rect padding,
                              int footerHeight, int headerHeight, View anchorView) {
        int menuHeight;
        anchorView.getLocationOnScreen(mTempLocation);
        int anchorViewY = mTempLocation[1] - appDimensions.top;

        int anchorViewImpactHeight = mIsByPermanentButton ? anchorView.getHeight() : 0;

        // Set appDimensions.height() for abnormal anchorViewLocation.
        if (anchorViewY > screenHeight) {
            anchorViewY = appDimensions.height();
        }
        int availableScreenSpace = Math.max(
                anchorViewY, appDimensions.height() - anchorViewY - anchorViewImpactHeight);

        availableScreenSpace -= (padding.bottom + footerHeight + headerHeight);
        if (mIsByPermanentButton) availableScreenSpace -= padding.top;

        int numCanFit = availableScreenSpace / (mItemRowHeight + mItemDividerHeight);

        // Fade out the last item if we cannot fit all items.
        if (numCanFit < numMenuItems) {
            int spaceForFullItems = numCanFit * (mItemRowHeight + mItemDividerHeight);
            spaceForFullItems += footerHeight + headerHeight;

            int spaceForPartialItem = (int) (LAST_ITEM_SHOW_FRACTION * mItemRowHeight);
            // Determine which item needs hiding.
            if (spaceForFullItems + spaceForPartialItem < availableScreenSpace) {
                menuHeight = spaceForFullItems + spaceForPartialItem + padding.top + padding.bottom;
            } else {
                menuHeight = spaceForFullItems - mItemRowHeight + spaceForPartialItem + padding.top
                        + padding.bottom;
            }
        } else {
            int spaceForFullItems = numMenuItems * (mItemRowHeight + mItemDividerHeight);
            spaceForFullItems += footerHeight + headerHeight;
            menuHeight = spaceForFullItems + padding.top + padding.bottom;
        }
        mPopup.setHeight(menuHeight);
        return menuHeight;
    }

    private void runMenuItemEnterAnimations() {
        mMenuItemEnterAnimator = new AnimatorSet();
        AnimatorSet.Builder builder = null;

        ViewGroup list = mListView;
        for (int i = 0; i < list.getChildCount(); i++) {
            View view = list.getChildAt(i);
            Object animatorObject = view.getTag(R.id.menu_item_enter_anim_id);
            if (animatorObject != null) {
                if (builder == null) {
                    builder = mMenuItemEnterAnimator.play((Animator) animatorObject);
                } else {
                    builder.with((Animator) animatorObject);
                }
            }
        }

        /*mMenuItemEnterAnimator.addListener(mAnimationHistogramRecorder);*/
        mMenuItemEnterAnimator.start();
    }

    private int inflateFooter(
            int footerResourceId, View contentView, int menuWidth, Integer highlightedItemId) {
        if (footerResourceId == 0) {
            return 0;
        }

        ViewStub footerStub = contentView.findViewById(R.id.app_menu_footer_stub);
        footerStub.setLayoutResource(footerResourceId);
        View mFooterView = footerStub.inflate();

        int widthMeasureSpec = MeasureSpec.makeMeasureSpec(menuWidth, MeasureSpec.EXACTLY);
        int heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        mFooterView.measure(widthMeasureSpec, heightMeasureSpec);

        if (highlightedItemId != null) {
            View viewToHighlight = mFooterView.findViewById(highlightedItemId);
            ViewHighlighter.turnOnHighlight(viewToHighlight, viewToHighlight != mFooterView);
        }

        if (mHandler != null) mHandler.onFooterViewInflated(mFooterView);

        return mFooterView.getMeasuredHeight();
    }

    private int inflateHeader(int headerResourceId, View contentView, int menuWidth) {
        if (headerResourceId == 0) return 0;

        View headerView = LayoutInflater.from(contentView.getContext())
                .inflate(headerResourceId, mListView, false);
        mListView.addHeaderView(headerView);

        int widthMeasureSpec = MeasureSpec.makeMeasureSpec(menuWidth, MeasureSpec.EXACTLY);
        int heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        headerView.measure(widthMeasureSpec, heightMeasureSpec);

        if (mHandler != null) mHandler.onHeaderViewInflated(headerView);

        return headerView.getMeasuredHeight();
    }

    /**
     * Handles long clicks on image buttons on the AppMenu popup.
     *
     * @param menuItem The menu item in the popup that was long clicked.
     * @param view     The anchor view of the menu item.
     */
    boolean onItemLongClick(MenuItem menuItem, View view) {
        if (!menuItem.isEnabled()) return false;

        String description;
        Context context = view.getContext();
//        Resources resources = context.getResources();
//        final int itemId = menuItem.getItemId();

        description = menuItem.getTitle().toString();
//        if (itemId == R.id.forward_menu_id) {
//            description = resources.getString(R.string.menu_forward);
//        } else if (itemId == R.id.bookmark_this_page_id) {
//            description = resources.getString(R.string.menu_bookmark);
//        } else if (itemId == R.id.offline_page_id) {
//            description = resources.getString(R.string.menu_download);
//        } else if (itemId == R.id.info_menu_id) {
//            description = resources.getString(R.string.menu_page_info);
//        } else if (itemId == R.id.reload_menu_id) {
//            description = (menuItem.getIcon().getLevel()
//                    == resources.getInteger(R.integer.reload_button_level_reload))
//                    ? resources.getString(R.string.menu_refresh)
//                    : resources.getString(R.string.menu_stop_refresh);
//        }
        return showAccessibilityToast(context, view, description);
    }
}

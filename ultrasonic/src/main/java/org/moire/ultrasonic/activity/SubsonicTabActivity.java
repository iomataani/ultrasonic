/*
 This file is part of Ultrasonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.activity;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import timber.log.Timber;
import android.view.*;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.*;
import net.simonvt.menudrawer.MenuDrawer;
import net.simonvt.menudrawer.Position;
import static org.koin.java.KoinJavaComponent.inject;

import org.koin.java.KoinJavaComponent;
import org.moire.ultrasonic.R;
import org.moire.ultrasonic.data.ActiveServerProvider;
import org.moire.ultrasonic.domain.MusicDirectory;
import org.moire.ultrasonic.domain.MusicDirectory.Entry;
import org.moire.ultrasonic.domain.PlayerState;
import org.moire.ultrasonic.domain.Share;
import org.moire.ultrasonic.featureflags.Feature;
import org.moire.ultrasonic.featureflags.FeatureStorage;
import org.moire.ultrasonic.fragment.SelectAlbumFragment;
import org.moire.ultrasonic.service.*;
import org.moire.ultrasonic.subsonic.ImageLoaderProvider;
import org.moire.ultrasonic.subsonic.SubsonicImageLoaderProxy;
import org.moire.ultrasonic.subsonic.loader.image.SubsonicImageLoader;
import org.moire.ultrasonic.util.*;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import java.util.regex.Pattern;

import kotlin.Lazy;

/**
 * @author Sindre Mehus
 */
public class SubsonicTabActivity extends ResultActivity
{
	private static final Pattern COMPILE = Pattern.compile(":");
	protected static String theme;
	private static SubsonicTabActivity instance;

	private boolean destroyed;

	private static final String STATE_MENUDRAWER = "org.moire.ultrasonic.menuDrawer";
	private static final String STATE_ACTIVE_VIEW_ID = "org.moire.ultrasonic.activeViewId";
	private static final String STATE_ACTIVE_POSITION = "org.moire.ultrasonic.activePosition";
	private static final int DIALOG_ASK_FOR_SHARE_DETAILS = 102;

	private Lazy<MediaPlayerController> mediaPlayerControllerLazy = inject(MediaPlayerController.class);
	private Lazy<MediaPlayerLifecycleSupport> lifecycleSupport = inject(MediaPlayerLifecycleSupport.class);
	protected Lazy<ImageLoaderProvider> imageLoader = inject(ImageLoaderProvider.class);

	public MenuDrawer menuDrawer;
	private int activePosition = 1;
	private int menuActiveViewId;
	private View nowPlayingView;
	View chatMenuItem;
	View bookmarksMenuItem;
	View sharesMenuItem;
	public static boolean nowPlayingHidden;
	boolean licenseValid;
	private EditText shareDescription;
	TimeSpanPicker timeSpanPicker;
	CheckBox hideDialogCheckBox;
	CheckBox noExpirationCheckBox;
	CheckBox saveAsDefaultsCheckBox;
	ShareDetails shareDetails;

	@Override
	protected void onCreate(Bundle bundle)
	{
		super.onCreate(bundle);

		setVolumeControlStream(AudioManager.STREAM_MUSIC);

		if (bundle != null)
		{
			activePosition = bundle.getInt(STATE_ACTIVE_POSITION);
			menuActiveViewId = bundle.getInt(STATE_ACTIVE_VIEW_ID);
		}

		menuDrawer = MenuDrawer.attach(this, MenuDrawer.Type.BEHIND, Position.LEFT, MenuDrawer.MENU_DRAG_WINDOW);
		menuDrawer.setMenuView(R.layout.menu_main);

		chatMenuItem = findViewById(R.id.menu_chat);
		bookmarksMenuItem = findViewById(R.id.menu_bookmarks);
		sharesMenuItem = findViewById(R.id.menu_shares);

		setActionBarDisplayHomeAsUp(true);

		TextView activeView = (TextView) findViewById(menuActiveViewId);

		if (activeView != null)
		{
			menuDrawer.setActiveView(activeView);
		}
	}

	@Override
	protected void onPostCreate(Bundle bundle)
	{
		super.onPostCreate(bundle);
		instance = this;

		int visibility = ActiveServerProvider.Companion.isOffline(this) ? View.GONE : View.VISIBLE;
		chatMenuItem.setVisibility(visibility);
		bookmarksMenuItem.setVisibility(visibility);
		sharesMenuItem.setVisibility(visibility);
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		Util.applyTheme(this);
		instance = this;

		Util.registerMediaButtonEventReceiver(this, false);
		// Lifecycle support's constructor registers some event receivers so it should be created early
		lifecycleSupport.getValue().onCreate();

		// Make sure to update theme
		if (theme != null && !theme.equals(Util.getTheme(this)))
		{
			theme = Util.getTheme(this);
			restart();
		}

		// This must be filled here because onCreate is called before the derived objects would call setContentView
		getNowPlayingView();

		if (!nowPlayingHidden)
		{
			showNowPlaying();
		}
		else
		{
			hideNowPlaying();
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{
			case android.R.id.home:
				menuDrawer.toggleMenu();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy()
	{
		Util.unregisterMediaButtonEventReceiver(this, false);
		super.onDestroy();
		destroyed = true;
		nowPlayingView = null;
		imageLoader.getValue().clearImageLoader();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		boolean isVolumeDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
		boolean isVolumeUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
		boolean isVolumeAdjust = isVolumeDown || isVolumeUp;
		boolean isJukebox = getMediaPlayerController() != null && getMediaPlayerController().isJukeboxEnabled();

		if (isVolumeAdjust && isJukebox)
		{
			getMediaPlayerController().adjustJukeboxVolume(isVolumeUp);
			return true;
		}

		return super.onKeyDown(keyCode, event);
	}

	protected void restart()
	{
		Intent intent = new Intent(this, this.getClass());
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		intent.putExtras(getIntent());
		startActivityForResultWithoutTransition(this, intent);
		Timber.d("Restarting activity...");
	}

	@Override
	public void finish()
	{
		super.finish();
		Util.disablePendingTransition(this);
	}

	@Override
	public boolean isDestroyed()
	{
		return destroyed;
	}

	private void getNowPlayingView()
	{
		if (nowPlayingView == null)
		{
			try {
				nowPlayingView = findViewById(R.id.now_playing);
			}
			catch (Exception exception) {
				Timber.w(exception, "An exception has occurred while trying to get the nowPlayingView by findViewById");
			}
		}
	}

	public void showNowPlaying()
	{
		this.runOnUiThread(new Runnable()
		{
			@Override
			public void run()
			{
				new SilentBackgroundTask<Void>(SubsonicTabActivity.this)
				{
					@Override
					protected Void doInBackground() throws Throwable
					{
						if (!Util.getShowNowPlayingPreference(SubsonicTabActivity.this))
						{
							hideNowPlaying();
							return null;
						}

						if (nowPlayingView != null)
						{
							PlayerState playerState = mediaPlayerControllerLazy.getValue().getPlayerState();

							if (playerState.equals(PlayerState.PAUSED) || playerState.equals(PlayerState.STARTED))
							{
								DownloadFile file = mediaPlayerControllerLazy.getValue().getCurrentPlaying();

								if (file != null)
								{
									final Entry song = file.getSong();
									showNowPlaying(SubsonicTabActivity.this, mediaPlayerControllerLazy.getValue(), song, playerState);
								}
							}
							else
							{
								hideNowPlaying();
							}
						}

						return null;
					}

					@Override
					protected void done(Void result)
					{
					}
				}.execute();
			}
		});
	}

	private void showNowPlaying(final Context context, final MediaPlayerController mediaPlayerController, final Entry song, final PlayerState playerState)
	{
		if (context == null || mediaPlayerController == null || song == null || playerState == null)
		{
			return;
		}

		if (!Util.getShowNowPlayingPreference(context))
		{
			hideNowPlaying();
			return;
		}

		if (nowPlayingView != null)
		{
			try
			{
				setVisibilityOnUiThread(nowPlayingView, View.VISIBLE);
				nowPlayingHidden = false;

				ImageView playButton = (ImageView) nowPlayingView.findViewById(R.id.now_playing_control_play);

				if (playerState == PlayerState.PAUSED)
				{
					setImageDrawableOnUiThread(playButton, Util.getDrawableFromAttribute(context, R.attr.media_play));
				}
				else if (playerState == PlayerState.STARTED)
				{
					setImageDrawableOnUiThread(playButton, Util.getDrawableFromAttribute(context, R.attr.media_pause));
				}

				String title = song.getTitle();
				String artist = song.getArtist();

				final ImageView nowPlayingAlbumArtImage = (ImageView) nowPlayingView.findViewById(R.id.now_playing_image);
				TextView nowPlayingTrack = (TextView) nowPlayingView.findViewById(R.id.now_playing_trackname);
				TextView nowPlayingArtist = (TextView) nowPlayingView.findViewById(R.id.now_playing_artist);

				this.runOnUiThread(new Runnable()
				{
					@Override
					public void run()
					{
						imageLoader.getValue().getImageLoader().loadImage(nowPlayingAlbumArtImage, song, false, Util.getNotificationImageSize(context), false, true);
					}
				});

				// TODO: Refactor to use navigation
				final Intent intent = new Intent(context, SelectAlbumFragment.class);// SelectAlbumActivity.class);

				if (Util.getShouldUseId3Tags(context))
				{
					intent.putExtra(Constants.INTENT_EXTRA_NAME_IS_ALBUM, true);
					intent.putExtra(Constants.INTENT_EXTRA_NAME_ID, song.getAlbumId());
				}
				else
				{
					intent.putExtra(Constants.INTENT_EXTRA_NAME_IS_ALBUM, false);
					intent.putExtra(Constants.INTENT_EXTRA_NAME_ID, song.getParent());
				}

				intent.putExtra(Constants.INTENT_EXTRA_NAME_NAME, song.getAlbum());

				setOnClickListenerOnUiThread(nowPlayingAlbumArtImage, new OnClickListener()
				{
					@Override
					public void onClick(View view)
					{
						startActivityForResultWithoutTransition(SubsonicTabActivity.this, intent);
					}
				});

				setTextOnUiThread(nowPlayingTrack, title);
				setTextOnUiThread(nowPlayingArtist, artist);

				ImageView nowPlayingControlPlay = (ImageView) nowPlayingView.findViewById(R.id.now_playing_control_play);

				SwipeDetector swipeDetector = new SwipeDetector(SubsonicTabActivity.this, mediaPlayerController);
				setOnTouchListenerOnUiThread(nowPlayingView, swipeDetector);

				setOnClickListenerOnUiThread(nowPlayingView, new OnClickListener()
				{
					@Override
					public void onClick(View v)
					{
					}
				});

				setOnClickListenerOnUiThread(nowPlayingControlPlay, new OnClickListener()
				{
					@Override
					public void onClick(View view)
					{
						mediaPlayerController.togglePlayPause();
					}
				});

			}
			catch (Exception x)
			{
				Timber.w(x, "Failed to get notification cover art");
			}
		}
	}

	public void hideNowPlaying()
	{
		try
		{
			if (nowPlayingView != null)
			{
				setVisibilityOnUiThread(nowPlayingView, View.GONE);
			}
		}
		catch (Exception ex)
		{
			Timber.w(ex, "Exception in hideNowPlaying");
		}
	}

	public void setOnTouchListenerOnUiThread(final View view, final OnTouchListener listener)
	{
		this.runOnUiThread(new Runnable()
		{
			@Override
			public void run()
			{
				if (view != null && view.getVisibility() != View.GONE)
				{
					view.setOnTouchListener(listener);
				}
			}
		});
	}

	public void setOnClickListenerOnUiThread(final View view, final OnClickListener listener)
	{
		this.runOnUiThread(new Runnable()
		{
			@Override
			public void run()
			{
				if (view != null && view.getVisibility() != View.GONE)
				{
					view.setOnClickListener(listener);
				}
			}
		});
	}

	public void setTextOnUiThread(final TextView view, final CharSequence text)
	{
		this.runOnUiThread(new Runnable()
		{
			@Override
			public void run()
			{
				if (view != null && view.getVisibility() != View.GONE)
				{
					view.setText(text);
				}
			}
		});
	}

	public void setImageDrawableOnUiThread(final ImageView view, final Drawable drawable)
	{
		this.runOnUiThread(new Runnable()
		{
			@Override
			public void run()
			{
				if (view != null && view.getVisibility() != View.GONE)
				{
					view.setImageDrawable(drawable);
				}
			}
		});
	}

	public void setVisibilityOnUiThread(final View view, final int visibility)
	{
		this.runOnUiThread(new Runnable()
		{
			@Override
			public void run()
			{
				if (view != null && view.getVisibility() != visibility)
				{
					view.setVisibility(visibility);
				}
			}
		});
	}

	public static SubsonicTabActivity getInstance()
	{
		return instance;
	}

	public MediaPlayerController getMediaPlayerController()
	{
		return mediaPlayerControllerLazy.getValue();
	}

	protected void setActionBarDisplayHomeAsUp(boolean enabled)
	{
		ActionBar actionBar = getSupportActionBar();

		if (actionBar != null)
		{
			actionBar.setDisplayHomeAsUpEnabled(enabled);
		}
	}

	@Override
	protected void onRestoreInstanceState(Bundle inState)
	{
		super.onRestoreInstanceState(inState);
		menuDrawer.restoreState(inState.getParcelable(STATE_MENUDRAWER));
	}

	@Override
	protected void onSaveInstanceState(Bundle outState)
	{
		super.onSaveInstanceState(outState);
		outState.putParcelable(STATE_MENUDRAWER, menuDrawer.saveState());
		outState.putInt(STATE_ACTIVE_VIEW_ID, menuActiveViewId);
		outState.putInt(STATE_ACTIVE_POSITION, activePosition);
	}

	@Override
	public void onBackPressed()
	{
		final int drawerState = menuDrawer.getDrawerState();

		if (drawerState == MenuDrawer.STATE_OPEN || drawerState == MenuDrawer.STATE_OPENING)
		{
			menuDrawer.closeMenu(true);
			return;
		}

		super.onBackPressed();
	}

	protected class SwipeDetector implements OnTouchListener
	{
		public SwipeDetector(SubsonicTabActivity activity, final MediaPlayerController mediaPlayerController)
		{
			this.mediaPlayerController = mediaPlayerController;
			this.activity = activity;
		}

		private static final int MIN_DISTANCE = 30;
		private float downX, downY, upX, upY;
		private MediaPlayerController mediaPlayerController;
		private SubsonicTabActivity activity;

		@Override
		public boolean onTouch(View v, MotionEvent event)
		{
			switch (event.getAction())
			{
				case MotionEvent.ACTION_DOWN:
				{
					downX = event.getX();
					downY = event.getY();
					return false;
				}
				case MotionEvent.ACTION_UP:
				{
					upX = event.getX();
					upY = event.getY();

					float deltaX = downX - upX;
					float deltaY = downY - upY;

					if (Math.abs(deltaX) > MIN_DISTANCE)
					{
						// left or right
						if (deltaX < 0)
						{
							mediaPlayerController.previous();
							return false;
						}
						if (deltaX > 0)
						{
							mediaPlayerController.next();
							return false;
						}
					}
					else if (Math.abs(deltaY) > MIN_DISTANCE)
					{
						if (deltaY < 0)
						{
							SubsonicTabActivity.nowPlayingHidden = true;
							activity.hideNowPlaying();
							return false;
						}
						if (deltaY > 0)
						{
							return false;
						}
					}

					// TODO: Refactor this to Navigation. It should automatically go to the PlayerFragment.
					//SubsonicTabActivity.this.startActivityForResultWithoutTransition(activity, DownloadActivity.class);
					return false;
				}
			}

			return false;
		}
	}
}

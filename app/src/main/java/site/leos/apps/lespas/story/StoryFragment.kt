/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.story

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.Animatable2
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.BGMDialogFragment
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.File
import java.time.LocalDateTime

class StoryFragment : Fragment() {
    private lateinit var album: Album
    private var isRemote: Boolean = false
    private var isPublication: Boolean = false
    private lateinit var serverPath: String
    private lateinit var serverFullPath: String
    private lateinit var publicationPath: String
    private lateinit var rootPath: String
    private var total = 0

    private val animationHandler = Handler(Looper.getMainLooper())

    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: StoryAdapter

    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var playerViewModel: VideoPlayerViewModel

    private var previousTitleBarDisplayOption = 0

    private var hasBGM = false
    private var isMuted = false
    private lateinit var bgmPlayer: ExoPlayer
    private var fadingJob: Job? = null
    private lateinit var localPath: String


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = requireArguments().parcelable(KEY_ALBUM)!!
        isRemote = Tools.isRemoteAlbum(album)
        isPublication = album.eTag == Photo.ETAG_FAKE
        rootPath = Tools.getLocalRoot(requireContext())
        publicationPath = imageLoaderModel.getResourceRoot()
        serverPath = "${Tools.getRemoteHome(requireContext())}/${album.name}"
        serverFullPath = "${publicationPath}${serverPath}"

        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache(), slideshowMode = true))[VideoPlayerViewModel::class.java]
        // Advance to next slide after video playback end
        playerViewModel.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                if (playbackState == Player.STATE_ENDED) {
                    animationHandler.postDelayed({
                        if (slider.currentItem < (slider.adapter as StoryAdapter).currentList.size - 1) {
                            if (!(slider.adapter as StoryAdapter).isSlideVideo(slider.currentItem + 1)) fadeInBGM()
                            slider.setCurrentItem(slider.currentItem + 1, true)
                            // Fake drag to next photo
                            //slider.beginFakeDrag()
                            //slider.fakeDragBy(-width * 0.8f)
                            //slider.endFakeDrag()
                        } else stopSlideshow()
                    }, 100)
                }
            }
        })

        pAdapter = StoryAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            playerViewModel,
            { rp ->
                with(rp.photo) {
                    val uri = when {
                        isPublication -> Uri.parse("${publicationPath}${rp.remotePath}/${name}")
                        isRemote && eTag != Photo.ETAG_NOT_YET_UPLOADED -> Uri.parse("${serverFullPath}/${name}")
                        else -> {
                            var fileName = "${rootPath}/${id}"
                            if (!(File(fileName).exists())) fileName = "${rootPath}/${name}"
                            Uri.parse("file:///$fileName")
                        }
                    }
                    SeamlessMediaSliderAdapter.VideoItem(uri, mimeType, width, height, id)
                }
            },
            { state -> },
            { photo, imageView, type -> if (type != NCShareViewModel.TYPE_NULL) imageLoaderModel.setImagePhoto(photo, imageView!!, type) },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        // Prepare display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requireActivity().window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // Wipe ActionBar
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            previousTitleBarDisplayOption = savedInstanceState?.run {
                // During fragment recreate, wipe actionbar to avoid flash
                wipeActionBar()

                getInt(KEY_DISPLAY_OPTION)
            } ?: displayOptions
        }
        Tools.goImmersive(requireActivity().window)

        // Prepare BGM playing
        localPath = Tools.getLocalRoot(requireContext())
        bgmPlayer = ExoPlayer.Builder(requireContext()).build()
        bgmPlayer.run {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = false

            var bgmFile = "$localPath/${album.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
            if (File(bgmFile).exists()) setBGM(bgmFile)
            else {
                // BGM for publication downloaded in cache folder in PublicationDetailFragment
                bgmFile = "${requireContext().cacheDir}/${album.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
                if (File(bgmFile).exists()) setBGM(bgmFile)
            }

            if (hasBGM) {
                bgmPlayer.volume = 0f
                bgmPlayer.prepare()
                // Mute the video sound during late night hours, otherwise fade in BGM
                if (LocalDateTime.now().hour in 7..22) fadeInBGM() else isMuted = true
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_story, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter
            setPageTransformer(ZoomInPageTransformer())

            // Without this line, the 2nd slide will most likely not be loaded in advanced, wired!
            offscreenPageLimit = 1
        }

        // Auto slider player with a dreamy style animation
        slider.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)

                // Activate here, rather than overwriting onPageSelected, is because onPageSelected is called before page transformer animation ends.
                // The problem doing here, is that when first slide shown, onPageScrollStateChanged is not called, we have to fake drag the first slide a little to kick start the whole process
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    animationHandler.removeCallbacksAndMessages(null)
                    animationHandler.postDelayed({
                        (slider.adapter as StoryAdapter).getViewHolderByPosition(slider.currentItem)?.apply {
                            when(this) {
                                is SeamlessMediaSliderAdapter<*>.PhotoViewHolder -> {
                                    this.getPhotoView().let { photoView ->
                                        // Stop any existing animation
                                        photoView.animation?.cancel()
                                        photoView.animation?.reset()

                                        // Start a dreamy animation by scaling image a little by 5% in a long period of time of 5s
                                        photoView.animate().setDuration(5000).scaleX(DREAMY_SCALE_FACTOR).scaleY(DREAMY_SCALE_FACTOR).setListener(object : Animator.AnimatorListener {
                                            override fun onAnimationStart(animation: Animator) {}
                                            override fun onAnimationRepeat(animation: Animator) {}
                                            override fun onAnimationCancel(animation: Animator) {}
                                            // Programmatically advance to the next slide after animation end
                                            //override fun onAnimationEnd(animation: Animator) { animationHandler.postDelayed(advanceSlide, 100) }
                                            override fun onAnimationEnd(animation: Animator) { animationHandler.post(advanceSlide) }
                                        })
                                    }
                                }
                                is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        this.getAnimatedDrawable().let {
                                            it.repeatCount = 1

                                            // This callback is unregistered when this AnimatedViewHolder is detached from window
                                            it.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                                                override fun onAnimationEnd(drawable: Drawable?) {
                                                    super.onAnimationEnd(drawable)
                                                    //animationHandler.postDelayed(advanceSlide, 100)
                                                    animationHandler.post(advanceSlide)
                                                }
                                            })
                                        }
                                    }
                                }
                                is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> {
                                    this.play()

                                    // For video item, auto forward to next slide is handled by player's listener
                                }
                            }
                        }
                    },200)
                }
            }
        })

        if (isPublication) {
            imageLoaderModel.publicationContentMeta.asLiveData().observe(viewLifecycleOwner) { startSlideshow(it) }
        } else {
            albumModel.getAllPhotoInAlbum(album.id).observe(viewLifecycleOwner) { photos ->
                Tools.sortPhotos(photos, album.sortOrder).run {
                    val rpList = mutableListOf<NCShareViewModel.RemotePhoto>()
                    forEach { rpList.add(NCShareViewModel.RemotePhoto(it, if (isRemote && it.eTag != Photo.ETAG_NOT_YET_UPLOADED) serverPath else "")) }
                    startSlideshow(rpList)
                }
            }
        }

        // Prevent screen rotation during slideshow playback
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    override fun onPause() {
        animationHandler.removeCallbacksAndMessages(null)
        playerViewModel.pause(Uri.EMPTY)
        stopSlideshow()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DISPLAY_OPTION, previousTitleBarDisplayOption)
    }

    override fun onDestroyView() {
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onDestroyView()
    }

    override fun onDestroy() {
        bgmPlayer.release()

        Tools.quitImmersive(requireActivity().window)

        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.run {
                displayOptions = previousTitleBarDisplayOption
                setBackgroundDrawable(ColorDrawable(Tools.getAttributeColor(requireContext(), android.R.attr.colorPrimary)))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requireActivity().window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

        super.onDestroy()
    }

    private fun wipeActionBar() {
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            displayOptions = 0
        }
    }

    private fun startSlideshow(photos: List<NCShareViewModel.RemotePhoto>) {
        total = photos.size - 1
        if (Tools.isMediaPlayable(photos[0].photo.mimeType)) fadeOutBGM()
        pAdapter.setPhotos(photos) {
            // Kick start the slideshow by fake drag a bit on the first slide, so that onPageScrollStateChanged can be called
            slider.beginFakeDrag()
            slider.fakeDragBy(1f)
            slider.endFakeDrag()
        }
    }

    private fun stopSlideshow() {
        fadeOutBGM()
    }

    private fun setBGM(bgmFile: String) {
        bgmPlayer.setMediaItem(MediaItem.fromUri("file://${bgmFile}"))
        hasBGM = true
    }

    private fun fadeInBGM() {
        if (hasBGM) {
            fadingJob?.cancel()

            fadingJob = lifecycleScope.launch {
                bgmPlayer.play()
                while (isActive) {
                    delay(75)

                    if (bgmPlayer.volume < 1f) bgmPlayer.volume += 0.05f
                    else {
                        bgmPlayer.volume = 1f
                        break
                    }
                }
            }
        }
    }

    private fun fadeOutBGM() {
        if (hasBGM) {
            fadingJob?.cancel()

            if (bgmPlayer.volume > 0f) {
                fadingJob = lifecycleScope.launch {
                    while (isActive) {
                        delay(75)

                        if (bgmPlayer.volume > 0f) bgmPlayer.volume -= 0.05f
                        else {
                            bgmPlayer.volume = 0f
                            bgmPlayer.pause()
                            break
                        }
                    }
                }
            }
        }
    }

    private val advanceSlide = Runnable {
        var prevValue = 0
        if (slider.currentItem < (slider.adapter as StoryAdapter).currentList.size - 1) {
            // fade out BGM if next slide is video, do it here to prevent audio mix up
            if ((slider.adapter as StoryAdapter).isSlideVideo(slider.currentItem + 1)) fadeOutBGM()

            // Slow down the default page transformation speed
            ValueAnimator.ofInt(0, slider.width).run {
                duration = 800
                //interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    (it.animatedValue as Int).run {
                        slider.fakeDragBy((prevValue - this).toFloat())
                        prevValue = this
                    }
                }
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) { slider.beginFakeDrag() }
                    override fun onAnimationEnd(animation: Animator) { slider.endFakeDrag() }
                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                })
                start()
            }
        }
        else stopSlideshow()
    }

    // ViewPager2 PageTransformer for zooming out current slide and zooming in the next
    class ZoomInPageTransformer: ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            when(page) {
                !is PlayerView -> {
                    page.apply {
                        when {
                            position <= -1f -> { // [-Infinity, -1)
                                // This page is way off-screen to the left
                                alpha = 0f
                                scaleX = 1f
                                scaleY = 1f
                            }
                            position < 0f -> { // [-1, 0)
                                // This page is moving off-screen

                                alpha = 1f + position
                                // Counteract the default slide transition
                                translationX = width * -position
                                // Move it above the page on the right, which is the one becoming the center piece
                                translationZ = 1f
                                (DREAMY_SCALE_FACTOR - position).run {
                                    scaleX = this
                                    scaleY = this
                                }
                            }
                            position <= 1f -> { // [0, 1]
                                // This page is moving into screen

                                alpha = 1f - position
                                // Counteract the default slide transition
                                translationX = width * -position
                                translationZ = 0f
                                //(0.5f * (1 - position) + 0.5f).run {
                                (0.5f * (2f - position)).run {
                                    scaleX = this
                                    scaleY = this
                                }
                            }
                            else -> { // (1, +Infinity]
                                // This page is way off-screen to the right
                                alpha = 0f
                                translationZ = -1f
                                scaleX = 0.5f
                                scaleY = 0.5f
                            }
                        }
                    }
                }
            }
        }
    }

    class StoryAdapter(
        context: Context,
        displayWidth: Int, playerViewModel: VideoPlayerViewModel, private val videoItemLoader: (NCShareViewModel.RemotePhoto) -> VideoItem,
        clickListener: (Boolean?) -> Unit, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = videoItemLoader(getItem(position))
        override fun getItemTransitionName(position: Int): String = getItem(position).photo.id
        override fun getItemMimeType(position: Int): String = getItem(position).photo.mimeType

        fun setPhotos(photos: List<NCShareViewModel.RemotePhoto>, callback: () -> Unit) { submitList(photos.toMutableList()) { callback() }}

        fun isSlideVideo(position: Int): Boolean = currentList[position].photo.mimeType.startsWith("video")

        // Maintaining a map between adapter position and it's ViewHolder
        private val vhMap = HashMap<ViewHolder, Int>()
        override fun onViewAttachedToWindow(holder: ViewHolder) {
            super.onViewAttachedToWindow(holder)
            vhMap[holder] = holder.bindingAdapterPosition
        }

        override fun onViewDetachedFromWindow(holder: ViewHolder) {
            vhMap.remove(holder)
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            vhMap.remove(holder)
            super.onViewRecycled(holder)
        }
        fun getViewHolderByPosition(position: Int): ViewHolder? {
            vhMap.entries.forEach { if (it.value == position) return it.key }
            return null
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
    }

    companion object {
        private const val DREAMY_SCALE_FACTOR = 1.05f
        private const val KEY_DISPLAY_OPTION = "KEY_DISPLAY_OPTION"

        private const val KEY_ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = StoryFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) }}
    }
}
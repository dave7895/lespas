package site.leos.apps.lespas.photo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumDetailFragment
import site.leos.apps.lespas.helper.ImageLoaderViewModel

class PhotoSlideFragment : Fragment() {
    private lateinit var album: Album
    private var startAt: Int = 0
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter
    private lateinit var photosModel: PhotoViewModel     // TODO naming
    private val currentPhotoModel: CurrentPhotoViewModel by activityViewModels()
    private val uiModel: UIViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()

    companion object {
        private const val ALBUM = "ALBUM"
        private const val POSITION = "POSITION"

        fun newInstance(album: Album, position: Int) = PhotoSlideFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ALBUM, album)
                putInt(POSITION, position)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(ALBUM)!!
        startAt = savedInstanceState?.getInt(POSITION) ?: arguments?.getInt(POSITION)!!

        photosModel = ViewModelProvider(this, AlbumDetailFragment.PhotosViewModelFactory(this.requireActivity().application, album.id)).get(PhotoViewModel::class.java)
        //sharedElementEnterTransition = ChangeBounds()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_photoslide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pAdapter = PhotoSlideAdapter(
            object : PhotoSlideAdapter.OnTouchListener {
                override fun onTouch() {
                    uiModel.toggleOnOff()
                }
            },
            object : PhotoSlideAdapter.OnLoadImage {
                override fun loadImage(photo: Photo, view: ImageView, type: String) {
                    imageLoaderModel.loadPhoto(photo, view, type)
                }
            }
        )

        photosModel.allPhotoInAlbum.observe(viewLifecycleOwner, { photos->
            pAdapter.setPhotos(photos)
            (view.parent as? ViewGroup)?.doOnPreDraw { slider.setCurrentItem(startAt, false) }
        })

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    currentPhotoModel.setCurrentPhoto(photosModel.allPhotoInAlbum.value!![position])
                }
            })
        }

        // TODO: should be started when view loaded
        // Briefly show controls
        uiModel.hideUI()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        (requireActivity() as AppCompatActivity).supportActionBar?.hide()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(POSITION, slider.currentItem)
    }

    override fun onPause() {
        super.onPause()

        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    class PhotoSlideAdapter(private val itemListener: OnTouchListener, private val imageLoader: OnLoadImage) : RecyclerView.Adapter<PhotoSlideAdapter.PagerViewHolder>() {
        private var photos = emptyList<Photo>()

        interface OnTouchListener {
            fun onTouch()
        }

        interface OnLoadImage {
            fun loadImage(photo: Photo, view: ImageView, type: String)
        }

        inner class PagerViewHolder(private val itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(photo: Photo, itemListener: OnTouchListener) {
                itemView.findViewById<PhotoView>(R.id.media).apply {
                    imageLoader.loadImage(photo, this, ImageLoaderViewModel.TYPE_FULL)
                    setOnPhotoTapListener { _, _, _ -> itemListener.onTouch() }
                    setOnOutsidePhotoTapListener { itemListener.onTouch() }
                    maximumScale = 5.0f
                    mediumScale = 2.5f
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoSlideAdapter.PagerViewHolder {
            return PagerViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
        }

        override fun onBindViewHolder(holder: PhotoSlideAdapter.PagerViewHolder, position: Int) {
            holder.bindViewItems(photos[position], itemListener)
        }

        override fun getItemCount(): Int {
            return photos.size
        }

        fun setPhotos(collection: List<Photo>) {
            photos = collection
            notifyDataSetChanged()
        }
    }

    // Share current photo within this fragment and BottomControlsFragment and CropCoverFragment
    class CurrentPhotoViewModel : ViewModel() {
        private val photo = MutableLiveData<Photo>()

        fun getCurrentPhoto(): LiveData<Photo> { return photo }
        fun setCurrentPhoto(newPhoto: Photo) { photo.value = newPhoto }
    }

    // Share system ui visibility status with BottomControlsFragment
    class UIViewModel : ViewModel() {
        private val showUI = MutableLiveData<Boolean>()

        fun hideUI() { showUI.value = false }
        fun toggleOnOff() { showUI.value = !showUI.value!! }
        fun status(): LiveData<Boolean> { return showUI }
    }
}

/*
    class MyPhotoImageView @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null, defStyle: Int = 0
    ) : AppCompatImageView(context, attributeSet, defStyle) {
        init {
            super.setClickable(true)
            super.setOnTouchListener { v, event ->
                mScaleDetector.onTouchEvent(event)
                true
            }
        }
        private var mScaleFactor = 1f
        private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                mScaleFactor *= detector.scaleFactor
                mScaleFactor = Math.max(0.1f, Math.min(mScaleFactor, 5.0f))
                scaleX = mScaleFactor
                scaleY = mScaleFactor
                invalidate()
                return true
            }
        }
        private val mScaleDetector = ScaleGestureDetector(context, scaleListener)

        override fun onDraw(canvas: Canvas?) {
            super.onDraw(canvas)

            canvas?.apply {
                save()
                scale(mScaleFactor, mScaleFactor)
                restore()
            }
        }
    }
*/
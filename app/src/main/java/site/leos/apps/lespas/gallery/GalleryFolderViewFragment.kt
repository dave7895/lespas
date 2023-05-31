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

package site.leos.apps.lespas.gallery

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasEmptyView
import site.leos.apps.lespas.helper.LesPasFastScroller
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

class GalleryFolderViewFragment : Fragment(), ActionMode.Callback {
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var mediaList: RecyclerView
    private lateinit var yearIndicator: TextView
    private var actionMode: ActionMode? = null
    private lateinit var selectionTracker: SelectionTracker<String>
    private lateinit var selectionBackPressedCallback: OnBackPressedCallback
    private var spanCount = 0
    private lateinit var folderArgument: String

    private val galleryModel: GalleryFragment.GalleryViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val imageLoaderModel: NCShareViewModel by activityViewModels()

    private var stripExif = "2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        folderArgument = requireArguments().getString(ARGUMENT_FOLDER) ?: ""

        mediaAdapter = MediaAdapter(
            { view, photoId, mimeType ->
                if (mimeType.startsWith("video")) {
                    // Transition to surface view might crash some OEM phones, like Xiaomi
                    parentFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folderArgument), GallerySlideFragment::class.java.canonicalName).addToBackStack(null).commit()
                } else {
                    galleryModel.setCurrentPhotoId(photoId)

                    reenterTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        excludeTarget(view, true)
                    }
                    exitTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        excludeTarget(view, true)
                        excludeTarget(android.R.id.statusBarBackground, true)
                        excludeTarget(android.R.id.navigationBarBackground, true)
                    }

                    parentFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(view, view.transitionName)
                        .replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folderArgument), GallerySlideFragment::class.java.canonicalName)
                        .addToBackStack(null)
                        .commit()
                }
            },
            { photo, imageView -> imageLoaderModel.setImagePhoto(photo, imageView, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        selectionBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (selectionTracker.hasSelection()) {
                    selectionTracker.clearSelection()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, selectionBackPressedCallback)

        PreferenceManager.getDefaultSharedPreferences(requireContext()).apply {
            stripExif = getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) mediaList.findViewHolderForAdapterPosition(mediaAdapter.getPhotoPosition(galleryModel.getCurrentPhotoId()))?.let {
                    sharedElements?.put(names[0], it.itemView.findViewById(R.id.photo))
                }
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_gallery_list, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        yearIndicator = view.findViewById<TextView>(R.id.year_indicator).apply {
            doOnLayout {
                background = MaterialShapeDrawable().apply {
                    fillColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_error))
                    shapeAppearanceModel = ShapeAppearanceModel.builder().setTopLeftCorner(CornerFamily.CUT, yearIndicator.height.toFloat()).build()
                }
            }
        }
        mediaList = view.findViewById<RecyclerView?>(R.id.gallery_list).apply {
            mediaAdapter.setMarks(galleryModel.getPlayMark(), galleryModel.getSelectedMark())
            mediaAdapter.setDateStrings(getString(R.string.today), getString(R.string.yesterday))
            adapter = mediaAdapter

            spanCount = resources.getInteger(R.integer.cameraroll_grid_span_count)
            (layoutManager as GridLayoutManager).spanSizeLookup = object: GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (mediaAdapter.getItemViewType(position) == MediaAdapter.TYPE_DATE) spanCount else 1
                }
            }

            selectionTracker = SelectionTracker.Builder(
                "galleryFolderFragmentSelection",
                this,
                MediaAdapter.PhotoKeyProvider(mediaAdapter),
                MediaAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object: SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = !galleryModel.isPreparingShareOut() && key.isNotEmpty()
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = !galleryModel.isPreparingShareOut() && position > 0
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()
                        updateUI()
                    }

                    override fun onSelectionRestored() {
                        super.onSelectionRestored()
                        updateUI()
                    }

                    private fun updateUI() {
                        val selectionSize = selectionTracker.selection.size()
                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(this@GalleryFolderViewFragment)
                            actionMode?.let { it.title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize) }
                            selectionBackPressedCallback.isEnabled = true
                        } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                            selectionBackPressedCallback.isEnabled = false
                        } else actionMode?.title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize)
                    }
                })
            }
            mediaAdapter.setSelectionTracker(selectionTracker)
            savedInstanceState?.let { selectionTracker.onRestoreInstanceState(it) }

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = kotlinx.coroutines.Runnable {
                    TransitionManager.beginDelayedTransition(mediaList.parent as ViewGroup, Fade().apply { duration = 800 })
                    yearIndicator.visibility = View.GONE
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dx == 0 && dy == 0) {
                        // First entry or fragment resume false call, by layout re-calculation, hide dataIndicator
                        yearIndicator.isVisible = false
                    } else {
                        (recyclerView.layoutManager as GridLayoutManager).run {
                            if ((findLastCompletelyVisibleItemPosition() < mediaAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                                hideHandler.removeCallbacksAndMessages(null)
                                yearIndicator.let {
                                    it.text = mediaAdapter.currentList[findLastVisibleItemPosition()].photo.dateTaken.format(DateTimeFormatter.ofPattern("MMM uuuu"))
                                    it.isVisible = true
                                }
                                hideHandler.postDelayed(hideDateIndicator, 1500)
                            }
                        }
                    }
                }
            })

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_camera_roll_24)!!))

            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    mediaList.removeOnLayoutChangeListener(this)
                    val position = mediaAdapter.getPhotoPosition(galleryModel.getCurrentPhotoId()).run { if (this < 0) 0 else this }
                    mediaList.layoutManager?.let { layoutManager ->
                        layoutManager.findViewByPosition(position).let { view ->
                            if (view == null || layoutManager.isViewPartiallyVisible(view, false, true)) mediaList.post { layoutManager.scrollToPosition(position) }
                        }
                    }
                }
            })
        }

        LesPasFastScroller(
            mediaList,
            ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_track)!!,
            ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_track)!!,
            resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_width), 0, 0, resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_height)
        )

        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) galleryModel.remove(getSelectedPhotos())
                STRIP_REQUEST_KEY -> galleryModel.shareOut(getSelectedPhotos(), bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false), false)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            galleryModel.medias.collect { localMedias ->
                localMedias?.let {
                    val listGroupedByDate = mutableListOf<NCShareViewModel.RemotePhoto>()
                    var currentDate = LocalDate.now().plusDays(1)
                    for (media in (if (folderArgument.isNotEmpty()) localMedias.filter { it.folder == folderArgument } else {
                        localMedias.sortedByDescending { it.media.photo.dateTaken }
                    })) {
                        if (media.media.photo.dateTaken.toLocalDate() != currentDate) {
                            currentDate = media.media.photo.dateTaken.toLocalDate()
                            // Add a fake photo item by taking default value for nearly all properties, denotes a date separator
                            listGroupedByDate.add(NCShareViewModel.RemotePhoto(Photo(id = currentDate.toString(), albumId = GalleryFragment.FROM_DEVICE_GALLERY, dateTaken = media.media.photo.dateTaken, lastModified = media.media.photo.dateTaken, mimeType = "")))
                        }
                        listGroupedByDate.add(media.media)
                    }

                    if (listGroupedByDate.isEmpty()) parentFragmentManager.popBackStack() else mediaAdapter.submitList(listGroupedByDate)
                }
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.gallery_folder_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when(menuItem.itemId) {
                R.id.option_menu_calendar_view -> {
                    mediaAdapter.dateRange()?.let { dateRange ->
                        MaterialDatePicker.Builder.datePicker()
                            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(object: CalendarConstraints.DateValidator {
                                override fun describeContents(): Int = 0
                                override fun writeToParcel(dest: Parcel, flags: Int) {}
                                override fun isValid(date: Long): Boolean = mediaAdapter.hasDate(date)
                            }).setStart(dateRange.first).setEnd(dateRange.second).setOpenAt(mediaAdapter.getDateByPosition((mediaList.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())).build())
                            .setTheme(R.style.ThemeOverlay_LesPas_DatePicker)
                            .build()
                            .apply {
                                addOnPositiveButtonClickListener { picked ->
                                    val currentBottom = (mediaList.layoutManager as GridLayoutManager).findLastCompletelyVisibleItemPosition()
                                    mediaAdapter.getPositionByDate(picked).let { newPosition ->
                                        mediaList.findViewHolderForAdapterPosition(newPosition)?.itemView?.findViewById<TextView>(R.id.date)?.let { view ->
                                            // new position is visible on screen now
                                            if (newPosition == currentBottom) mediaList.scrollToPosition(newPosition + 1)
                                            flashDate(view)
                                        } ?: run {
                                            // flash the date after it has revealed
                                            mediaAdapter.setFlashDate(picked)
                                            mediaList.scrollToPosition(if (newPosition < currentBottom) newPosition else min(mediaAdapter.currentList.size - 1, newPosition + spanCount))
                                        }
                                    }
                                }
                            }.show(parentFragmentManager, null)
                    }
                    true
                }
                else -> false
            }

        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            title = requireArguments().getString(ARGUMENT_FOLDER)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try { selectionTracker.onSaveInstanceState(outState) } catch (_: UninitializedPropertyAccessException) {}
    }

    override fun onDestroyView() {
        mediaList.adapter = null

        super.onDestroyView()
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.action_mode_gallery, menu)

        return true
    }
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.add -> {
                galleryModel.add(getSelectedPhotos())
                true
            }
            R.id.remove -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) galleryModel.remove(getSelectedPhotos())
                else if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), requestKey = DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)

                true
            }
            R.id.share -> {
                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (hasExifInSelection()) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), requestKey = STRIP_REQUEST_KEY, positiveButtonText = getString(R.string.strip_exif_yes), negativeButtonText = getString(R.string.strip_exif_no), cancelable = true).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else galleryModel.shareOut(getSelectedPhotos(), false)
                } else galleryModel.shareOut(getSelectedPhotos(), stripExif == getString(R.string.strip_on_value))

                true
            }
            R.id.select_all -> {
                mediaAdapter.currentList.forEach { if (it.photo.mimeType.isNotEmpty()) selectionTracker.select(it.photo.id) }
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    private fun flashDate(view: View) {
        ObjectAnimator.ofPropertyValuesHolder(view, PropertyValuesHolder.ofFloat("translationX", 0f, 100f, 0f)).run {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            interpolator = BounceInterpolator()
            start()
        }
    }

    private fun hasExifInSelection(): Boolean {
        for (photoId in selectionTracker.selection) {
            galleryModel.getPhotoById(photoId.toString())?.let { if (Tools.hasExif(it.mimeType)) return true }
        }

        return false
    }

    private fun getSelectedPhotos(): List<String> = mutableListOf<String>().apply {
        selectionTracker.selection.forEach { add(it) }
        selectionTracker.clearSelection()
    }

    class MediaAdapter(private val clickListener: (View, String, String) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, RecyclerView.ViewHolder>(MediaDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var playMark: Drawable? = null
        private var selectedMark: Drawable? = null
        private val defaultOffset = OffsetDateTime.now().offset
        private var sToday = ""
        private var sYesterday = ""
        private var flashDateId = LocalDate.MIN

        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentId = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo).apply { foregroundGravity = Gravity.CENTER }

            fun bind(item: NCShareViewModel.RemotePhoto) {
                val photo = item.photo
                itemView.let {
                    it.isSelected = selectionTracker.isSelected(photo.id)

                    with(ivPhoto) {
                        if (currentId != photo.id) {
                            imageLoader(item, this)
                            currentId = photo.id
                        }

                        ViewCompat.setTransitionName(this, photo.id)

                        foreground = when {
                            it.isSelected -> selectedMark
                            Tools.isMediaPlayable(photo.mimeType) -> playMark
                            else -> null
                        }

                        if (it.isSelected) colorFilter = selectedFilter
                        else clearColorFilter()

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(this, photo.id, photo.mimeType) }
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        inner class HorizontalDateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val tvDate = itemView.findViewById<TextView>(R.id.date)

            @SuppressLint("SetTextI18n")
            fun bind(item: NCShareViewModel.RemotePhoto) {
                with(item.photo.dateTaken) {
                    val now = LocalDate.now()
                    val date = this.toLocalDate()
                    tvDate.text = when {
                        date == now -> sToday
                        date == now.minusDays(1) -> sYesterday
                        date.year == now.year -> "${format(DateTimeFormatter.ofPattern("MMM d"))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                        else -> "${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                    }
                }

                tvDate.setOnLongClickListener {
                    var index = currentList.indexOf(item)
                    while(true) {
                        index++
                        if (index == currentList.size) break
                        if (currentList[index].photo.mimeType.isEmpty()) break
                        selectionTracker.select(currentList[index].photo.id)
                    }
                    true
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_MEDIA) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))
            else HorizontalDateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date_horizontal, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MediaViewHolder) holder.bind(currentList[position])
            else (holder as HorizontalDateViewHolder).bind(currentList[position])
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> if (holder is MediaViewHolder) holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].photo.mimeType.isEmpty()) TYPE_DATE else TYPE_MEDIA

        internal fun setMarks(playMark: Drawable, selectedMark: Drawable) {
            this.playMark = playMark
            this.selectedMark = selectedMark
        }
        internal fun setDateStrings(today: String, yesterday: String) {
            sToday = today
            sYesterday = yesterday
        }
        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].photo.id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfLast { it.photo.id == photoId }

        fun hasDate(date: Long): Boolean {
            val theDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault()).toLocalDate()
            return (currentList.indexOfFirst { it.photo.mimeType.isEmpty() && it.photo.dateTaken.toLocalDate().isEqual(theDate) }) != RecyclerView.NO_POSITION
        }
        fun dateRange(): Pair<Long, Long>? {
            return if (currentList.isNotEmpty()) Pair(currentList.last().photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli(), currentList.first().photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli()) else null
        }
        fun setFlashDate(date: Long) { flashDateId = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault()).toLocalDate() }
        fun getPositionByDate(date: Long): Int = currentList.indexOfFirst { it.photo.mimeType.isEmpty() && it.photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli() - date < 86400000 }
        fun getDateByPosition(position: Int): Long = currentList[position].photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli()

        class PhotoKeyProvider(private val adapter: MediaAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    val holder = recyclerView.getChildViewHolder(it)
                    return if (holder is MediaViewHolder) holder.getItemDetails() else null
                }
                return null
            }
        }

        companion object {
            private const val TYPE_MEDIA = 0
            const val TYPE_DATE = 1
        }
    }
    class MediaDiffCallback : DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = true
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val STRIP_REQUEST_KEY = "GALLERY_STRIP_REQUEST_KEY"
        private const val DELETE_REQUEST_KEY = "GALLERY_DELETE_REQUEST_KEY"

        private const val ARGUMENT_FOLDER = "ARGUMENT_FOLDER"

        @JvmStatic
        fun newInstance(folder: String) = GalleryFolderViewFragment().apply { arguments = Bundle().apply { putString(ARGUMENT_FOLDER, folder) }}
    }
}
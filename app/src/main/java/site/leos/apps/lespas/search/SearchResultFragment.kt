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

package site.leos.apps.lespas.search

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.*
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.helper.LesPasEmptyView
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.tflite.ObjectDetectionModel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*

class SearchResultFragment : Fragment() {
    private lateinit var searchResultAdapter: SearchResultAdapter
    private lateinit var searchResultRecyclerView: RecyclerView
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val albumModel: AlbumViewModel by activityViewModels()
    private val adhocSearchViewModel: AdhocSearchViewModel by viewModels {
        AdhocAdhocSearchViewModelFactory(requireActivity().application, requireArguments().getString(CATEGORY_ID)!!, requireArguments().getInt(KEY_SEARCH_SCOPE), imageLoaderModel)
    }

    private var loadingIndicator: MenuItem? = null
    private var loadingProgressBar: CircularProgressIndicator? = null

    private var searchScope = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        searchScope = requireArguments().getInt(KEY_SEARCH_SCOPE)

        searchResultAdapter = SearchResultAdapter(
            searchScope,
            { result, imageView ->
                if (searchScope == R.id.search_album) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val album: Album = albumModel.getThisAlbum(result.remotePhoto.photo.albumId)
                        withContext(Dispatchers.Main) {
                            exitTransition = MaterialElevationScale(false).apply {
                                duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                                excludeTarget(imageView, true)
                            }
                            //reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                            parentFragmentManager.beginTransaction().setReorderingAllowed(true).addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                                .replace(R.id.container_root, AlbumDetailFragment.newInstance(album, result.remotePhoto.photo.id), AlbumDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
                        }
                    }
                }
                else {
                    reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                    exitTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                        excludeTarget(imageView, true)
                    }
                    //reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
/*
                    parentFragmentManager.beginTransaction().setReorderingAllowed(true).addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                        .replace(R.id.container_root, CameraRollFragment.newInstance(result.remotePhoto.photo.id, searchScope == R.id.search_archive), SearchResultFragment::class.java.canonicalName).addToBackStack(null).commit()
*/
                    parentFragmentManager.beginTransaction().setReorderingAllowed(true).addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                        .replace(R.id.container_root, GalleryFragment.newInstance(Uri.parse(result.remotePhoto.photo.id)), GalleryFragment::class.java.canonicalName).addToBackStack(null).commit()
                }
            },
            { remotePhoto: NCShareViewModel.RemotePhoto, view: ImageView -> imageLoaderModel.setImagePhoto(remotePhoto, view, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) }
        ).apply {
            // Get album's name for display
            lifecycleScope.launch(Dispatchers.IO) { setAlbumNameList(albumModel.getAllAlbumIdName()) }
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_search_result, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        searchResultRecyclerView = view.findViewById<RecyclerView?>(R.id.photo_grid).apply {
            adapter = searchResultAdapter

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(requireContext(),
                when(searchScope) {
                    R.id.search_album -> R.drawable.ic_baseline_footprint_24
                    R.id.search_archive -> R.drawable.ic_baseline_archive_24
                    else -> R.drawable.ic_baseline_device_24
                }
            )!!))

            // Avoid window inset overlapping
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val displayCutoutInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    rightMargin = displayCutoutInset.right + navigationBarInset.right
                    leftMargin = displayCutoutInset.left + navigationBarInset.left
                }
                insets
            }
        }

        adhocSearchViewModel.getResultList().observe(viewLifecycleOwner) { searchResult -> searchResultAdapter.submitList(searchResult.toMutableList()) }
        adhocSearchViewModel.getProgress().observe(viewLifecycleOwner) { progress ->
            when (progress) {
                0 -> loadingProgressBar?.isIndeterminate = true
                100 -> loadingIndicator?.apply {
                    isVisible = false
                    isEnabled = false
                }

                else -> loadingProgressBar?.apply {
                    isIndeterminate = false
                    setProgressCompat(progress, true)
                }
            }
        }

        if (searchResultAdapter.itemCount !=0 ) postponeEnterTransition()

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.search_result_menu, menu)
                loadingIndicator = menu.findItem(R.id.option_menu_search_progress).also {
                    loadingProgressBar = it.actionView?.findViewById(R.id.search_progress)
                    adhocSearchViewModel.getProgress().value?.also { progress->
                        if (progress == 100) {
                            it.isVisible = false
                            it.isEnabled = false
                        }
                    }
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = true
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            arguments?.let { title = getString(
                when(searchScope) {
                    R.id.search_album -> R.string.title_in_album
                    R.id.search_cameraroll -> R.string.title_in_device
                    else -> R.string.title_in_archive
                },
                it.getString(CATEGORY_LABEL))
            }
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onDestroyView() {
        searchResultRecyclerView.adapter = null
        super.onDestroyView()
    }

    class AdhocAdhocSearchViewModelFactory(private val application: Application, private val categoryId: String, private val searchTarget: Int, private val remoteImageModel: NCShareViewModel
    ): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(AdhocSearchViewModel(application, categoryId, searchTarget, remoteImageModel))!!
    }

    class AdhocSearchViewModel(app: Application, categoryId: String, searchTarget: Int, remoteImageModel: NCShareViewModel): AndroidViewModel(app) {
        private val resultList = mutableListOf<Result>()
        private val result = MutableLiveData<List<Result>>()
        private var job: Job? = null
        private val progress = SingleLiveEvent<Int>()

        init {
            // Run job in init(), since it's singleton
            job = viewModelScope.launch(Dispatchers.IO) {
                val albums = AlbumRepository(app).getAllAlbumAttribute()
                val od = ObjectDetectionModel(app.assets)
                val rootPath = Tools.getLocalRoot(app)
                val lespasBasePath = Tools.getRemoteHome(app)
                val cameraArchivePath = Tools.getCameraArchiveHome(app)
                var length: Int
                var size: Int
                val option = BitmapFactory.Options()
                var sharePath: String

                progress.postValue(0)
                val photos = when(searchTarget) {
                    R.id.search_album -> PhotoRepository(app).getAllImageNotHidden()
                    R.id.search_cameraroll -> Tools.listGalleryImages(app.contentResolver)
                    else -> remoteImageModel.getCameraRollArchive()
                }

                photos.forEachIndexed { i, photo ->
                    if (!isActive) return@launch
                    progress.postValue((i * 100.0 / photos.size).toInt())

                    // Decode file with dimension just above 300
                    size = 1
                    length = Integer.min(photo.width, photo.height)
                    while(length / size > 600) { size *= 2 }
                    option.inSampleSize = size
                    sharePath = ""  // Default sharePath string
                    try {
                        when(searchTarget) {
                            R.id.search_album -> {
                                albums.find { it.id == photo.albumId }?.let { album ->
                                    if (album.shareId and Album.REMOTE_ALBUM == Album.REMOTE_ALBUM && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
                                        // Photo's image file is not at local
                                        sharePath = "${lespasBasePath}/${album.name}"
                                        remoteImageModel.getPreview(NCShareViewModel.RemotePhoto(photo, sharePath))
                                    } else BitmapFactory.decodeFile("$rootPath/${photo.id}", option)
                                }
                            }
                            R.id.search_cameraroll -> BitmapFactory.decodeStream(app.contentResolver.openInputStream(Uri.parse(photo.id)), null, option)
                            R.id.search_archive -> {
                                sharePath = cameraArchivePath
                                remoteImageModel.getPreview(NCShareViewModel.RemotePhoto(photo, sharePath))
                            }
                            else -> null
                        }?.let {
                            // Inference
                            with(od.recognizeImage(it)) {
                                if (this.isNotEmpty()) with(this[0]) {
                                    if (this.classId == categoryId) {
                                        resultList.add(Result(NCShareViewModel.RemotePhoto(photo, sharePath), this.objectIndex, this.similarity))
                                        result.postValue(resultList)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                od.close()

                // Show progress to the end
                delay(500)
                progress.postValue(100)
            }
        }

        override fun onCleared() {
            // Stop search coroutine
            job?.cancel()

            super.onCleared()
        }

        fun getResultList(): LiveData<List<Result>> = result
        fun getProgress(): SingleLiveEvent<Int> = progress
    }

    class SearchResultAdapter(private val searchTarget: Int, private val clickListener: (Result, ImageView) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<Result, SearchResultAdapter.ViewHolder>(SearchResultDiffCallback()) {
        private val albumNames = HashMap<String, String>()

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentPhotoId = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)
            private val tvLabel = itemView.findViewById<TextView>(R.id.label)

            @SuppressLint("SetTextI18n")
            fun bind(item: Result) {
                with(ivPhoto) {
                    if (currentPhotoId != item.remotePhoto.photo.id) {
                        imageLoader(item.remotePhoto, this)
                        ViewCompat.setTransitionName(this, item.remotePhoto.photo.id)
                        currentPhotoId = item.remotePhoto.photo.id
                    }
                    setOnClickListener { clickListener(item, this) }
                }
                //tvLabel.text = "${item.subLabel}${String.format("  %.4f", item.similarity)}"
                tvLabel.text =
                    if (searchTarget == R.id.search_album) albumNames[item.remotePhoto.photo.albumId]
                    else item.remotePhoto.photo.dateTaken.run { "${this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}" }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_search_result, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            super.onDetachedFromRecyclerView(recyclerView)
        }

        fun setAlbumNameList(list: List<IDandName>) {
            for (album in list) { albumNames[album.id] = album.name }
        }
    }

    class SearchResultDiffCallback : DiffUtil.ItemCallback<Result>() {
        override fun areItemsTheSame(oldItem: Result, newItem: Result): Boolean {
            return oldItem.remotePhoto.photo.id == newItem.remotePhoto.photo.id
        }

        override fun areContentsTheSame(oldItem: Result, newItem: Result): Boolean {
            return oldItem == newItem
        }
    }

    data class Result(
        val remotePhoto: NCShareViewModel.RemotePhoto,
        val subLabelIndex: Int,
        val similarity: Float,
    )

    companion object {
        private const val KEY_SEARCH_SCOPE = "KEY_SEARCH_SCOPE"
        private const val CATEGORY_TYPE = "CATEGORY_TYPE"
        private const val CATEGORY_ID = "CATEGORY_ID"
        private const val CATEGORY_LABEL = "CATEGORY_LABEL"

        @JvmStatic
        fun newInstance(categoryType: Int, categoryId: String, categoryLabel: String, scope: Int) = SearchResultFragment().apply {
            arguments = Bundle().apply {
                putInt(CATEGORY_TYPE, categoryType)
                putString(CATEGORY_ID, categoryId)
                putString(CATEGORY_LABEL, categoryLabel)
                putInt(KEY_SEARCH_SCOPE, scope)
            }
        }
    }
}
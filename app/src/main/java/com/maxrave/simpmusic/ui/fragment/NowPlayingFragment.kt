package com.maxrave.simpmusic.ui.fragment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.customview.widget.ViewDragHelper
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.maxrave.simpmusic.R
import com.maxrave.simpmusic.common.Config
import com.maxrave.simpmusic.data.model.metadata.MetadataSong
import com.maxrave.simpmusic.databinding.FragmentNowPlayingBinding
import com.maxrave.simpmusic.utils.Resource
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.insetter.applyInsetter
import kotlinx.coroutines.launch


@UnstableApi
@AndroidEntryPoint
class NowPlayingFragment: Fragment() {
    private val UPDATE_INTERVAL_MS: Long = 1000
    private val viewModel by activityViewModels<SharedViewModel>()
    private lateinit var dragHelper: ViewDragHelper
    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private var metadataCurSong: MetadataSong? = null

    private var videoId: String? = null
    private var from: String? = null

    private var gradientDrawable: GradientDrawable? = null
    private var lyricsBackground: Int? = null

    private lateinit var songChangeListener: OnNowPlayingSongChangeListener
    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnNowPlayingSongChangeListener){
            songChangeListener = context
        }
        else{
            throw RuntimeException("$context must implement OnNowPlayingSongChangeListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        binding.topAppBar.applyInsetter {
            type(statusBars = true) {
                margin()
            }
        }
        binding.root.applyInsetter {
            type(navigationBars = true) {
                margin()
            }
        }
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity()
        val bottom = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation_view)
        val card = activity.findViewById<MaterialCardView>(R.id.card)
        bottom.visibility = View.GONE
        card.visibility = View.GONE
        binding.lyricsFullLayout.visibility = View.GONE
        binding.buffered.max = 100
        Log.d("check Video ID in ViewModel", viewModel.videoId.value.toString())
        videoId = arguments?.getString("videoId")
        from = arguments?.getString("from")
        Log.d("check Video ID in Fragment", videoId.toString())
        if (videoId != null) {
            if (viewModel.videoId.value == videoId)
            {
                gradientDrawable = viewModel.gradientDrawable.value
                lyricsBackground = viewModel.lyricsBackground.value
                metadataCurSong = viewModel.metadata.value?.data
                updateUI()
            }
            else
            {
                binding.ivArt.visibility = View.GONE
                binding.loadingArt.visibility = View.VISIBLE
                viewModel.gradientDrawable.postValue(null)
                viewModel.lyricsBackground.postValue(null)
                binding.tvSongTitle.visibility = View.GONE
                binding.tvSongArtist.visibility = View.GONE
                viewModel.getMetadata(videoId!!)
                observerMetadata()
            }
        }
        else {
            videoId = viewModel.videoId.value
            from = viewModel.from.value
            metadataCurSong = viewModel.metadata.value?.data
            gradientDrawable = viewModel.gradientDrawable.value
            lyricsBackground = viewModel.lyricsBackground.value
            updateUI()
        }

        lifecycleScope.launch {
            val job1 = launch {
                viewModel.progressString.observe(viewLifecycleOwner){
                    binding.tvCurrentTime.text = it
                    if (viewModel.progress.value * 100 in 0f..100f){
                        binding.progressSong.value = viewModel.progress.value * 100
                        songChangeListener.onUpdateProgressBar(viewModel.progress.value * 100)
                    }
                }
                viewModel.duration.collect {
                    binding.tvFullTime.text = viewModel.formatDuration(it)
                }
            }
            val job2 = launch {
                viewModel.isPlaying.observe(viewLifecycleOwner){
                    if (it){
                        binding.btPlayPause.setImageResource(R.drawable.baseline_pause_circle_24)
                        songChangeListener.onIsPlayingChange()
                    }
                    else{
                        binding.btPlayPause.setImageResource(R.drawable.baseline_play_circle_24)
                        songChangeListener.onIsPlayingChange()
                    }
                }
            }
            //Update progress bar from buffered percentage
            val job3 = launch {
                viewModel.bufferedPercentage.collect{
                    binding.buffered.progress = it
                    Log.d("buffered", it.toString())
                }
            }
            //Check if song is ready to play. And make progress bar indeterminate
            val job4 = launch {
                viewModel.notReady.observe(viewLifecycleOwner){
                    binding.buffered.isIndeterminate = it
                }
            }
            val job5 = launch {
                    viewModel.progressMillis.collect{
                        if (viewModel.metadata.value?.data != null){
                            val temp = viewModel.getLyricsString(it)
                            binding.tvSyncState.text = when (viewModel.getLyricsSyncState()){
                                Config.SyncState.NOT_FOUND -> null
                                Config.SyncState.LINE_SYNCED -> "Line Synced"
                                Config.SyncState.UNSYNCED -> "Unsynced"
                            }
                            if (temp != null){
                                if (temp.nowLyric == "Lyrics not found"){
                                    binding.lyricsLayout.visibility = View.GONE
                                    binding.lyricsTextLayout.visibility = View.GONE
                                }
                                else
                                {
                                    if (binding.btFull.text == "Show"){
                                        binding.lyricsTextLayout.visibility = View.VISIBLE
                                    }
                                    binding.lyricsLayout.visibility = View.VISIBLE
                                    if (temp.nowLyric != null){
                                        binding.tvNowLyrics.visibility = View.VISIBLE
                                        binding.tvNowLyrics.text = temp.nowLyric
                                    }
                                    else{
                                        binding.tvNowLyrics.visibility = View.GONE
                                    }
                                    if (temp.prevLyrics != null){
                                        binding.tvPrevLyrics.visibility = View.VISIBLE
                                        if (temp.prevLyrics.size > 1){
                                            val txt = temp.prevLyrics[0] + "\n" + temp.prevLyrics[1]
                                            binding.tvPrevLyrics.text = txt
                                        }
                                        else {
                                            binding.tvPrevLyrics.text = temp.prevLyrics[0]
                                        }
                                    }
                                    else{
                                        binding.tvPrevLyrics.visibility = View.GONE
                                    }
                                    if (temp.nextLyric != null){
                                        binding.tvNextLyrics.visibility = View.VISIBLE
                                        if (temp.nextLyric.size > 1){
                                            val txt = temp.nextLyric[0] + "\n" + temp.nextLyric[1]
                                            binding.tvNextLyrics.text = txt
                                        }
                                        else {
                                            binding.tvNextLyrics.text = temp.nextLyric[0]
                                        }
                                    }
                                    else{
                                        binding.tvNextLyrics.visibility = View.GONE
                                    }
                                }
                            }
                        }
                        else {
                            binding.lyricsLayout.visibility = View.GONE
                            binding.lyricsTextLayout.visibility = View.GONE
                        }
                }
            }
            val job6 = launch {
                viewModel.lyricsFull.observe(viewLifecycleOwner){
                    if (it != null){
                        binding.tvFullLyrics.text = it
                    }
                }
            }
            job1.join()
            job2.join()
            job3.join()
            job4.join()
            job5.join()
            job6.join()
        }
        binding.btFull.setOnClickListener {
            if (binding.btFull.text == "Show"){
                binding.btFull.text = "Hide"
                binding.lyricsTextLayout.visibility = View.GONE
                binding.lyricsFullLayout.visibility = View.VISIBLE
            }
            else{
                binding.btFull.text = "Show"
                binding.lyricsTextLayout.visibility = View.VISIBLE
                binding.lyricsFullLayout.visibility = View.GONE
            }
        }

        binding.progressSong.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {

            }

            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.onUIEvent(UIEvent.UpdateProgress(slider.value))
            }
        })

        binding.btPlayPause.setOnClickListener {
            viewModel.onUIEvent(UIEvent.PlayPause)
        }

        binding.topAppBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }
//
//        val player = ExoPlayer.Builder(requireContext()).build()
//        if (viewModel.currentSong.value == null){
//            viewModel.init()
//        }
//        var mediaItem = MediaItem.fromUri(viewModel.getStreamUrl())
//        player.addMediaItem(mediaItem)
//        player.prepare()
//        binding.progressSong.value = viewModel.currentSongPosition.value!!
//        if (viewModel.isPlaying) {
//            binding.btPlayPause.setImageResource(R.drawable.baseline_pause_24)
//        }
//        else {
//            binding.btPlayPause.setImageResource(R.drawable.baseline_play_arrow_24)
//        }
//        player.addListener(object : Player.Listener {
//            override fun onPlaybackStateChanged(state: Int) {
//                super.onPlaybackStateChanged(state)
//                when (state) {
//                    Player.STATE_BUFFERING -> {
//
//                    }
//                    Player.STATE_READY -> {
//                        binding.tvFullTime.text = time(player.duration)
//                        binding.progressSong.addOnChangeListener { slider, value, fromUser ->
//                            if (fromUser) {
//                                player.seekTo(value.toLong() * player.duration / 100)
//                            }
//                        }
//                    }
//                    Player.STATE_ENDED -> {
//                    }
//                    Player.STATE_IDLE -> {
//                    }
//                }
//            }
//        })
//        binding.btPlayPause.setOnClickListener {
//            if (viewModel.player.playbackState == Player.STATE_READY){
//
//            }
////            {
////                val handler = Handler(Looper.getMainLooper())
////                val updateProgressAction = object : Runnable {
////                    override fun run() { // get reference to your ExoPlayer instance
////                        val duration = player.duration
////                        val position = player.currentPosition
////                        binding.tvCurrentTime.text = time(position)
////                        Log.d("TAG", "run: $position")
////                        val bufferedPosition = player.bufferedPosition
////                        val percent = if (duration == 0L) 0f else (position * 100 / duration).toFloat()
////                        Log.d("TAG", "run: $percent")
//////                binding.progressSong.value = percent
////                        viewModel.currentSongPosition.value = percent
////                        observeViewModel(duration)
////                        binding.buffered.progress = (bufferedPosition * 100 / duration).toInt()
////                        handler.postDelayed(this, UPDATE_INTERVAL_MS)
////                    }
////                }
////                fun startUpdatingProgress() {
////                    handler.post(updateProgressAction)
////                }
////
////                fun stopUpdatingProgress() {
////                    handler.removeCallbacks(updateProgressAction)
////                }
////                if (viewModel.isPlaying) {
////                    stopUpdatingProgress()
////                    player.pause()
////                    viewModel.isPlaying = false
////                    binding.btPlayPause.setImageResource(R.drawable.baseline_play_arrow_24)
////
////                } else {
////                    player.play()
////                    startUpdatingProgress()
////                    viewModel.isPlaying = true
////                    binding.btPlayPause.setImageResource(R.drawable.baseline_pause_24)
////                    startUpdatingProgress()
////                }
//            else {
//                Toast.makeText(requireContext(), "Loading", Toast.LENGTH_SHORT).show()
//            }
//        }
//        Log.d("Duration", "onViewCreated: ${player.duration}")
//
//
//        binding.topAppBar.setNavigationOnClickListener {
//            dismiss()
//        }
//    }
    private fun removeTrailingComma(sentence: String): String {
        val trimmed = sentence.trimEnd()
        return if (trimmed.endsWith(", ")) {
            trimmed.dropLast(2)
        } else {
            trimmed
        }
    }


    private fun removeComma(string: String): String {
        return if (string.endsWith(',')) {
            string.substring(0, string.length - 1)
        } else {
            string
        }
    }
    private fun observerMetadata(){
        viewModel.videoId.postValue(videoId)
        viewModel.from.postValue(from)
        viewModel.metadata.observe(viewLifecycleOwner, Observer {
            when (it){
                is Resource.Success ->{
                    metadataCurSong = it.data
                    viewModel.loadMediaItems(videoId!!)
                    updateUI()
                }
                is Resource.Error ->{
                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show()
                }
                is Resource.Loading ->{

                }

                else -> {

                }
            }
        })
    }
    private fun updateUI(){
        binding.ivArt.visibility = View.GONE
        binding.loadingArt.visibility = View.VISIBLE
        val request = ImageRequest.Builder(requireContext())
            .data(metadataCurSong?.thumbnails?.last()?.url)
            .target(
                onStart = {
                    binding.ivArt.visibility = View.GONE
                    binding.loadingArt.visibility = View.VISIBLE
                    Log.d("Update UI", "onStart: ")
                },
                onSuccess = { result ->
                    binding.ivArt.visibility = View.VISIBLE
                    binding.loadingArt.visibility = View.GONE
                    binding.ivArt.setImageDrawable(result)
                    Log.d("Update UI", "onSuccess: ")
                    if (viewModel.gradientDrawable.value != null){
                        viewModel.gradientDrawable.observe(viewLifecycleOwner, Observer {
                            binding.rootLayout.background = it
//                            viewModel.lyricsBackground.observe(viewLifecycleOwner, Observer { color ->
//                                binding.lyricsLayout.setCardBackgroundColor(color)
//                                Log.d("Update UI", "Lyrics: $color")
//                                updateStatusBarColor(color)
//                            })
                            binding.lyricsLayout.setCardBackgroundColor(viewModel.lyricsBackground.value!!)
                        })
                        Log.d("Update UI", "updateUI: NULL")
                    }
                    songChangeListener.onNowPlayingSongChange()
                },
            )
            .transformations(object : Transformation{
                override val cacheKey: String
                    get() = "paletteArtTransformer"

                override suspend fun transform(input: Bitmap, size: Size): Bitmap {
                    val p = Palette.from(input).generate()
                    val defaultColor = 0x000000
                    var startColor = p.getDarkVibrantColor(defaultColor)
                    Log.d("Check Start Color", "transform: $startColor")
                    if (startColor == defaultColor){
                        startColor = p.getDarkMutedColor(defaultColor)
                        if (startColor == defaultColor){
                            startColor = p.getVibrantColor(defaultColor)
                            if (startColor == defaultColor){
                                startColor = p.getMutedColor(defaultColor)
                                if (startColor == defaultColor){
                                    startColor = p.getLightVibrantColor(defaultColor)
                                    if (startColor == defaultColor){
                                        startColor = p.getLightMutedColor(defaultColor)
                                    }
                                }
                            }
                        }
                        Log.d("Check Start Color", "transform: $startColor")
                    }
//                    val centerColor = 0x6C6C6C
                    val endColor = 0x1b1a1f
                    val gd = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(startColor, endColor)
                    )
                    gd.cornerRadius = 0f
                    gd.gradientType = GradientDrawable.LINEAR_GRADIENT
                    gd.gradientRadius = 0.5f
                    gd.alpha = 150
                    val bg = ColorUtils.setAlphaComponent(startColor, 230)
                    viewModel.gradientDrawable.postValue(gd)
                    viewModel.lyricsBackground.postValue(bg)
                    return input
                }

            })
            .build()
        ImageLoader(requireContext()).enqueue(request)
        Log.d("Metadata", metadataCurSong.toString())
        binding.topAppBar.subtitle = from
        binding.tvSongTitle.text = metadataCurSong?.title
        binding.tvSongTitle.isSelected = true
        var artistName = ""
        if (metadataCurSong?.artists != null) {
            for (artist in metadataCurSong!!.artists) {
                artistName += artist.name + ", "
            }
        }
        artistName = removeTrailingComma(artistName)
        artistName = removeComma(artistName)
        binding.tvSongArtist.text = artistName
        binding.tvSongArtist.isSelected = true
        binding.tvSongTitle.visibility = View.VISIBLE
        binding.tvSongArtist.visibility = View.VISIBLE

        Log.d("Metadata", metadataCurSong.toString())
    }
    fun updateStatusBarColor(color: Int) { // Color must be in hexadecimal format
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        val window: Window = requireActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("99"+color.toString())
    }
    public interface OnNowPlayingSongChangeListener{
        fun onNowPlayingSongChange()
        fun onIsPlayingChange()
        fun onUpdateProgressBar(progress: Float)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val activity = requireActivity()
        val bottom = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation_view)
        val card = activity.findViewById<MaterialCardView>(R.id.card)
        bottom.visibility = View.VISIBLE
        card.visibility = View.VISIBLE
    }


}
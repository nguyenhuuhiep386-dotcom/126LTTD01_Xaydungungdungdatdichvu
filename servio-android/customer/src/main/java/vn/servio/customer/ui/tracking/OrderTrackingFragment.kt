package vn.servio.customer.ui.tracking

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import vn.servio.core.ui.chat.ChatFragment
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.repository.OrderUpdates
import vn.servio.customer.databinding.FragmentOrderTrackingBinding
import vn.servio.customer.databinding.ViewTrackingPartnerMarkerBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.map.MapConfig
import vn.servio.customer.ui.order.OrderStatusChip
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.order.TrackingUi
import vn.servio.customer.ui.orderdetail.ArrivalPrompt
import vn.servio.customer.ui.orderdetail.CancelOrderDialogFragment
import vn.servio.customer.ui.requestdetail.QuoteTexts
import javax.inject.Inject

/**
 * CS-21 Theo dõi bản đồ. Markers are only moved to the positions the server sent (no route, no interpolation). The
 * camera fits both markers once, above the sheet; later it only refits when the partner leaves the visible part.
 * Map setup, tiles and attribution as CS-12 ([MapConfig]).
 */
@AndroidEntryPoint
class OrderTrackingFragment : Fragment(R.layout.fragment_order_tracking) {

    @Inject lateinit var httpClient: OkHttpClient

    @Inject lateinit var orderUpdates: OrderUpdates

    private val viewModel: OrderTrackingViewModel by viewModels()

    private var binding: FragmentOrderTrackingBinding? = null
    private var mapView: MapView? = null
    private var partnerMarker: Marker? = null
    private var partnerMarkerName: String? = null
    private var addressMarker: Marker? = null
    private var fitted = false
    private var arrivalPrompt: ArrivalPrompt? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOrderTrackingBinding.bind(view).also { binding = it }
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())
        fitted = false

        binding.attribution.setText(MapConfig.ATTRIBUTION)
        mapView = binding.map.apply {
            setTileSource(MapConfig.TILE_SOURCE)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
        }
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.cancelButton.setOnClickListener { openCancel() }

        arrivalPrompt = ArrivalPrompt(
            host = this,
            orderUpdates = orderUpdates,
            orderId = { viewModel.orderId },
            names = { viewModel.state.value.detail?.let { assignmentPartner()?.displayName to it.address } },
            reload = { viewModel.load() },
        ).also { it.attach() }
        // Cancelled from here: the order leaves ON_THE_WAY, the reload sends Leave and CS-20 shows the result.
        childFragmentManager.setFragmentResultListener(CancelOrderDialogFragment.RESULT_KEY, viewLifecycleOwner) { _, _ -> viewModel.load() }

        collectWhenStarted(viewModel.state) { state ->
            val detail = state.detail
            binding.progress.isVisible = state.loading && detail == null
            binding.errorGroup.isVisible = detail == null && state.error != null
            binding.errorText.text = state.error
            binding.sheet.visibility = if (detail != null) View.VISIBLE else View.INVISIBLE
            if (detail != null) {
                bindSheet(binding, images, state)
                bindMap(binding, state)
                arrivalPrompt?.showIfPending()
            }
            bindLive(binding)
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                OrderTrackingViewModel.Event.Leave -> backToOrder()
            }
        }
        // Staleness is decided by the phone's clock: re-check every 5 s while started.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(TrackingUi.CHECK_EVERY_MILLIS)
                    bindLive(binding)
                }
            }
        }
    }

    private fun assignmentPartner() = viewModel.state.value.detail
        ?.let { OrderTrackingViewModel.assignmentOf(it, viewModel.assignmentId) }?.partner

    private fun bindSheet(binding: FragmentOrderTrackingBinding, images: RemoteImages, state: OrderTrackingViewModel.State) {
        val detail = state.detail ?: return
        val assignment = OrderTrackingViewModel.assignmentOf(detail, viewModel.assignmentId)
        val partner = assignment?.partner
        val name = partner?.displayName.orEmpty()
        binding.partnerName.text = name.ifBlank { getString(R.string.order_missing_value) }
        binding.avatarInitials.text = QuoteTexts.initials(name)
        val avatarUrl = partner?.avatarUrl
        if (binding.avatarImage.getTag(R.id.avatarImage) != avatarUrl) {
            binding.avatarImage.setTag(R.id.avatarImage, avatarUrl)
            images.load(avatarUrl, binding.avatarImage)
        }
        val rating = OrderTexts.rating(partner?.rating, partner?.totalReviews)
        with(binding.rating) {
            star.isVisible = rating.score != null
            score.isVisible = rating.score != null
            score.text = rating.score
            count.isVisible = rating.score == null || rating.reviewCount != null
            count.maxLines = 1
            count.text = when {
                rating.score == null -> getString(R.string.quote_no_reviews)
                rating.reviewCount != null -> getString(R.string.quote_review_count, rating.reviewCount)
                else -> null
            }
        }
        OrderStatusChip.bind(binding.statusChip, OrderStatus.ON_THE_WAY)

        val accepted = OrderTexts.time(assignment?.acceptedAt ?: detail.acceptedAt)
        val moving = OrderTexts.time(assignment?.onTheWayAt)
        binding.note.text = if (accepted != null && moving != null) {
            getString(R.string.tracking_note, accepted, moving)
        } else {
            getString(R.string.tracking_note_no_times)
        }

        val conversationId = detail.conversationId
        val phone = partner?.phoneNumber
        binding.chatButton.isVisible = conversationId != null
        binding.chatButton.setOnClickListener { conversationId?.let(::openChat) }
        binding.callButton.isVisible = phone != null
        binding.callButton.setOnClickListener { phone?.let(::dial) }
        binding.contactGap.isVisible = conversationId != null && phone != null
        binding.contactRow.isVisible = conversationId != null || phone != null
    }

    /** Pill, ETA and distance: they depend on the clock as well as on the state. */
    private fun bindLive(binding: FragmentOrderTrackingBinding) {
        val context = requireContext()
        val view = TrackingUi.of(viewModel.state.value.tracking)
        val secondary = ContextCompat.getColor(context, vn.servio.core.R.color.servio_text_secondary)
        val primary = ContextCompat.getColor(context, vn.servio.core.R.color.servio_text_primary)
        val missing = getString(R.string.tracking_missing)
        when (view) {
            is TrackingUi.View.Live -> {
                binding.updatedText.text = OrderTexts.clock(view.capturedAt)?.let { getString(R.string.tracking_updated, it) }
                binding.updatedText.setTextColor(primary)
                binding.etaValue.text = getString(R.string.tracking_eta_value, view.etaMinutes)
                binding.distanceValue.text = getString(R.string.order_distance_km, TrackingUi.distance(view.distanceKm))
            }
            is TrackingUi.View.Stale -> {
                binding.updatedText.text = OrderTexts.clock(view.capturedAt)?.let { getString(R.string.tracking_stale, it) }
                    ?: getString(R.string.order_eta_unknown)
                binding.updatedText.setTextColor(secondary)
                binding.etaValue.text = missing
                binding.distanceValue.text = missing
            }
            TrackingUi.View.NoLocation -> {
                binding.updatedText.setText(R.string.tracking_no_location)
                binding.updatedText.setTextColor(secondary)
                binding.etaValue.text = missing
                binding.distanceValue.text = missing
            }
            TrackingUi.View.Unknown -> {
                binding.updatedText.setText(R.string.order_eta_unknown)
                binding.updatedText.setTextColor(secondary)
                binding.etaValue.text = missing
                binding.distanceValue.text = missing
            }
        }
        binding.updatedIcon.imageTintList = ColorStateList.valueOf(if (view is TrackingUi.View.Live) primary else secondary)
    }

    private fun bindMap(binding: FragmentOrderTrackingBinding, state: OrderTrackingViewModel.State) {
        val map = mapView ?: return
        val detail = state.detail ?: return
        val address = detail.latitude?.let { lat -> detail.longitude?.let { lng -> GeoPoint(lat, lng) } }
        if (address != null && addressMarker == null) {
            addressMarker = Marker(map).apply {
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.marker_address)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = getString(R.string.tracking_address_marker)
                setInfoWindow(null)
                position = address
            }.also { map.overlays.add(it) }
        }
        val location = state.tracking?.location
        val partnerPoint = location?.let { GeoPoint(it.latitude, it.longitude) }
        if (partnerPoint != null) {
            val name = assignmentPartner()?.displayName.orEmpty()
            val marker = partnerMarker ?: Marker(map).apply {
                title = getString(R.string.tracking_partner_marker)
                setInfoWindow(null)
            }.also {
                partnerMarker = it
                map.overlays.add(it)
            }
            if (partnerMarkerName != name) {
                partnerMarkerName = name
                setPartnerIcon(marker, name)
            }
            marker.position = partnerPoint
        }
        map.invalidate()

        val points = listOfNotNull(partnerPoint, address)
        if (points.isEmpty()) return
        binding.root.doOnLayout {
            when {
                !fitted -> {
                    fitted = fit(binding, points)
                }
                partnerPoint != null && !isVisible(binding, partnerPoint) -> fit(binding, points)
            }
        }
    }

    /** The partner marker as a bitmap: yellow circle with the arrow, the name below; anchored on the circle's centre. */
    private fun setPartnerIcon(marker: Marker, name: String) {
        val view = ViewTrackingPartnerMarkerBinding.inflate(LayoutInflater.from(requireContext()))
        view.name.text = name
        view.name.isVisible = name.isNotBlank()
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.root.measure(spec, spec)
        view.root.layout(0, 0, view.root.measuredWidth, view.root.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.root.measuredWidth, view.root.measuredHeight, Bitmap.Config.ARGB_8888)
        view.root.draw(Canvas(bitmap))
        marker.icon = BitmapDrawable(resources, bitmap)
        val circleCentre = view.circle.top + view.circle.height / 2f
        marker.setAnchor(Marker.ANCHOR_CENTER, circleCentre / view.root.measuredHeight)
    }

    /**
     * Top of the visible map (below the floating controls) and its bottom (above the attribution and the sheet, less
     * room for the name label under the partner marker), in map pixels.
     */
    private fun visibleBand(binding: FragmentOrderTrackingBinding): Pair<Int, Int> =
        binding.updatedPill.bottom to binding.bottom.top - (LABEL_ROOM_DP * resources.displayMetrics.density).toInt()

    private fun isVisible(binding: FragmentOrderTrackingBinding, point: GeoPoint): Boolean {
        val map = mapView ?: return true
        val pixel = map.projection.toPixels(point, Point())
        val (top, bottom) = visibleBand(binding)
        return pixel.x in 0..map.width && pixel.y in top..bottom
    }

    /**
     * Fits [points] into the part of the map between the controls and the sheet: the box is stretched north and south
     * by the share of the screen they cover, then fitted with a margin. False when the map has no size yet.
     */
    private fun fit(binding: FragmentOrderTrackingBinding, points: List<GeoPoint>): Boolean {
        val map = mapView ?: return false
        if (map.width == 0 || map.height == 0) return false
        val (top, bottom) = visibleBand(binding)
        val usable = (bottom - top).coerceAtLeast(map.height / 3)
        var north = points.maxOf { it.latitude }
        var south = points.minOf { it.latitude }
        var east = points.maxOf { it.longitude }
        var west = points.minOf { it.longitude }
        if (north - south < MIN_SPAN) {
            val middle = (north + south) / 2
            north = middle + MIN_SPAN / 2
            south = middle - MIN_SPAN / 2
        }
        if (east - west < MIN_SPAN) {
            val middle = (east + west) / 2
            east = middle + MIN_SPAN / 2
            west = middle - MIN_SPAN / 2
        }
        // Zoom so the box would fit in the visible band (its height scaled to the whole map), centred on the markers…
        val middle = (north + south) / 2
        val half = (north - south) / 2 * map.height / usable
        map.zoomToBoundingBox(BoundingBox(middle + half, east, middle - half, west), false, (FIT_MARGIN_DP * resources.displayMetrics.density).toInt())
        // … then, once that zoom is drawn, move the map so the markers' middle sits in the middle of the band.
        val target = GeoPoint(middle, (east + west) / 2)
        map.post {
            val current = mapView ?: return@post
            val pixel = current.projection.toPixels(target, Point())
            val shift = pixel.y - (top + bottom) / 2
            current.controller.setCenter(current.projection.fromPixels(current.width / 2, current.height / 2 + shift))
        }
        return true
    }

    private fun openCancel() {
        if (childFragmentManager.findFragmentByTag(CancelOrderDialogFragment.TAG) != null) return
        val code = viewModel.state.value.detail?.code.orEmpty()
        CancelOrderDialogFragment.newInstance(viewModel.orderId, code).show(childFragmentManager, CancelOrderDialogFragment.TAG)
    }

    /** Back to CS-20 of this order (it reloads itself through OrderStatusChanged). */
    private fun backToOrder() {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs21) return
        if (!navController.popBackStack(R.id.cs20, false)) navController.navigateUp()
    }

    private fun openChat(conversationId: String) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs21) return
        navController.navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to conversationId))
    }

    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (_: ActivityNotFoundException) {
            // No phone app: nothing else to do.
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        mapView?.onDetach()
        mapView = null
        partnerMarker = null
        partnerMarkerName = null
        addressMarker = null
        arrivalPrompt = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val DEFAULT_ZOOM = 15.0

        /** Smallest box side in degrees (~200 m), so a single marker is not zoomed in to the maximum. */
        const val MIN_SPAN = 0.002
        const val FIT_MARGIN_DP = 48

        /** Height of the name label below the partner marker. */
        const val LABEL_ROOM_DP = 32
    }
}

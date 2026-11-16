package vn.servio.partner.ui.order

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import vn.servio.core.ui.chat.ChatFragment
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.databinding.FragmentNavigateBinding
import vn.servio.partner.tracking.LocationTrackingService
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.map.MapConfig
import vn.servio.partner.ui.order.NavigateViewModel.Event
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** PS-15 Điều hướng tới khách. The tracking service keeps sending the position; this screen handles arriving. */
@AndroidEntryPoint
class NavigateFragment : Fragment(R.layout.fragment_navigate) {

    private val viewModel: NavigateViewModel by viewModels()
    private var mapView: MapView? = null
    private var customerMarker: Marker? = null
    private var meMarker: Marker? = null
    private var framed = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentNavigateBinding.bind(view)
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.attribution.setText(MapConfig.ATTRIBUTION)
        mapView = binding.map.apply {
            setTileSource(MapConfig.TILE_SOURCE)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
        }
        binding.arriveButton.setOnClickListener { viewModel.arrive() }
        binding.askCustomerButton.setOnClickListener { viewModel.askCustomer() }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorText.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            if (state is UiState.Error) binding.errorText.text = state.message
            if (state is UiState.Content) bind(binding, state.data)
        }
        collectWhenStarted(viewModel.me) { fix ->
            if (fix == null) return@collectWhenStarted
            val map = mapView ?: return@collectWhenStarted
            val point = GeoPoint(fix.latitude, fix.longitude)
            val marker = meMarker ?: Marker(map).also {
                it.title = getString(R.string.navigate_me)
                it.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(it)
                meMarker = it
            }
            marker.position = point
            frame()
            updateDistance(binding)
            map.invalidate()
        }
        collectWhenStarted(viewModel.problem.combine(viewModel.waitingUntil) { problem, waiting -> problem to waiting }) { (problem, waiting) ->
            binding.problemCard.isVisible = problem != null
            binding.problemText.text = problem?.message ?: problem?.messageRes?.takeIf { it != 0 }?.let(::getString)
            binding.askCustomerButton.isVisible = problem?.canAskCustomer == true && waiting == null
            binding.waitingText.isVisible = waiting != null
            binding.waitingText.text = getString(
                R.string.navigate_waiting,
                Texts.parse(waiting)?.let(Texts::time).orEmpty(),
            )
        }
        collectWhenStarted(viewModel.busy) { busy ->
            binding.arriveButton.isEnabled = !busy
            binding.askCustomerButton.isEnabled = !busy
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                Event.Arrived -> {
                    LocationTrackingService.stop(requireContext())
                    Snackbar.make(view, R.string.navigate_arrived_done, Snackbar.LENGTH_LONG).show()
                    findNavController().navigateUp()
                }
                is Event.Message -> Snackbar.make(view, event.text, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun bind(binding: FragmentNavigateBinding, order: OrderDetail) {
        binding.destination.text = getString(R.string.navigate_destination, order.customer.displayName)
        binding.address.text = order.address
        val lat = order.latitude
        val lng = order.longitude
        binding.directionsButton.isVisible = lat != null && lng != null
        binding.directionsButton.setOnClickListener { if (lat != null && lng != null) openDirections(lat, lng) }
        binding.chatButton.isVisible = order.conversationId != null
        binding.chatButton.setOnClickListener {
            order.conversationId?.let { findNavController().navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to it)) }
        }
        val phone = order.customer.phoneNumber
        binding.callButton.isVisible = phone != null
        binding.callButton.setOnClickListener { phone?.let { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) } }

        val map = mapView ?: return
        if (lat != null && lng != null) {
            val marker = customerMarker ?: Marker(map).also {
                it.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_map_pin)
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(it)
                customerMarker = it
            }
            marker.title = order.address
            marker.position = GeoPoint(lat, lng)
            frame()
            updateDistance(binding)
            map.invalidate()
        }
    }

    /** Shows both markers once, then leaves the camera to the user. */
    private fun frame() {
        if (framed) return
        val map = mapView ?: return
        val points = listOfNotNull(customerMarker?.position, meMarker?.position)
        when (points.size) {
            1 -> map.controller.setCenter(points.first())
            2 -> {
                framed = true
                map.post { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, FRAME_PADDING_PX) }
            }
        }
    }

    private fun updateDistance(binding: FragmentNavigateBinding) {
        val from = meMarker?.position
        val to = customerMarker?.position
        binding.distance.isVisible = from != null && to != null
        if (from != null && to != null) {
            val km = haversineKm(from.latitude, from.longitude, to.latitude, to.longitude)
            binding.distance.text = if (km < 1) {
                getString(R.string.navigate_distance_m, (km * 1000).toInt())
            } else {
                getString(R.string.navigate_distance_km, Texts.distance(km))
            }
        }
    }

    private fun openDirections(lat: Double, lng: Double) {
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            view?.let { Snackbar.make(it, R.string.navigate_no_map_app, Snackbar.LENGTH_LONG).show() }
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
        customerMarker = null
        meMarker = null
        framed = false
        super.onDestroyView()
    }

    private companion object {
        const val DEFAULT_ZOOM = 16.0
        const val FRAME_PADDING_PX = 160

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

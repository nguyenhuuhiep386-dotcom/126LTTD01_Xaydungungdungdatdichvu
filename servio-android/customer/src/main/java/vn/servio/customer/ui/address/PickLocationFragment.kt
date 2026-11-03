package vn.servio.customer.ui.address

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.Address
import vn.servio.customer.databinding.FragmentPickLocationBinding
import vn.servio.customer.ui.address.PickLocationViewModel.Event
import vn.servio.customer.ui.address.PickLocationViewModel.FieldError
import vn.servio.customer.ui.address.PickLocationViewModel.MapCamera
import vn.servio.customer.ui.map.MapConfig

/**
 * CS-12 Chọn vị trí: adds an address, or edits the one given by the nav argument `addressId`.
 * The pin stays at the centre of the map; the saved coordinates are the map centre.
 */
@AndroidEntryPoint
class PickLocationFragment : Fragment(R.layout.fragment_pick_location) {

    private val viewModel: PickLocationViewModel by viewModels()

    private var mapView: MapView? = null
    private var locationRequest: CancellationSignal? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                moveToCurrentLocation(showErrors = true)
            } else {
                showMessage(R.string.pick_location_permission_denied)
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPickLocationBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.attribution.setText(MapConfig.ATTRIBUTION)
        mapView = binding.map.apply {
            setTileSource(MapConfig.TILE_SOURCE)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            val camera = viewModel.camera ?: MapCamera(DEFAULT_LATITUDE, DEFAULT_LONGITUDE, DEFAULT_ZOOM)
            controller.setZoom(camera.zoom)
            controller.setCenter(GeoPoint(camera.latitude, camera.longitude))
            // The map sits inside the scrolling form: keep drag gestures on the map.
            setOnTouchListener { v, _ ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }

        binding.locateButton.setOnClickListener {
            if (hasLocationPermission()) {
                moveToCurrentLocation(showErrors = true)
            } else {
                locationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                )
            }
        }
        binding.labelChips.setOnCheckedStateChangeListener { _, checkedIds ->
            binding.customLabelLayout.isVisible = R.id.chipOther in checkedIds
        }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.saveButton.setOnClickListener {
            val center = binding.map.mapCenter
            viewModel.save(
                fullAddress = binding.addressInput.text?.toString().orEmpty(),
                note = binding.noteInput.text?.toString().orEmpty(),
                label = binding.selectedLabel(),
                isDefault = binding.defaultCheck.isChecked,
                latitude = center.latitude,
                longitude = center.longitude,
            )
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorGroup.isVisible = state is UiState.Error
            binding.form.isVisible = state is UiState.Content
            binding.updateSaveButton()
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.errorText.text = state.message
                is UiState.Content -> {
                    val address = state.data.address
                    // The current default cannot be unset here; the user picks another default instead.
                    binding.defaultCheck.isEnabled = address?.isDefault != true
                    if (!viewModel.isFormFilled) {
                        viewModel.isFormFilled = true
                        binding.fill(address)
                    }
                }
            }
        }
        collectWhenStarted(viewModel.fieldErrors) { errors ->
            binding.addressLayout.error =
                if (FieldError.ADDRESS_REQUIRED in errors) getString(R.string.pick_location_address_required) else null
            binding.customLabelLayout.error =
                if (FieldError.LABEL_REQUIRED in errors) getString(R.string.pick_location_label_required) else null
        }
        collectWhenStarted(viewModel.saving) { binding.updateSaveButton() }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                Event.Saved -> findNavController().navigateUp()
                is Event.Error -> Snackbar.make(view, event.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        rememberCamera()
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        locationRequest?.cancel()
        locationRequest = null
        mapView?.onDetach()
        mapView = null
        super.onDestroyView()
    }

    private fun rememberCamera() {
        val map = mapView ?: return
        viewModel.camera = MapCamera(map.mapCenter.latitude, map.mapCenter.longitude, map.zoomLevelDouble)
    }

    private fun FragmentPickLocationBinding.updateSaveButton() {
        saveButton.isEnabled = viewModel.state.value is UiState.Content && !viewModel.saving.value
    }

    private fun FragmentPickLocationBinding.fill(address: Address?) {
        if (address == null) {
            // New address: start at the current position when the permission was already granted.
            if (hasLocationPermission()) moveToCurrentLocation(showErrors = false)
            return
        }
        addressInput.setText(address.fullAddress)
        noteInput.setText(address.note)
        when (address.label) {
            getString(R.string.address_label_home) -> chipHome.isChecked = true
            getString(R.string.address_label_work) -> chipWork.isChecked = true
            else -> {
                chipOther.isChecked = true
                customLabelInput.setText(address.label)
            }
        }
        defaultCheck.isChecked = address.isDefault
        map.controller.setCenter(GeoPoint(address.latitude, address.longitude))
    }

    private fun FragmentPickLocationBinding.selectedLabel(): String = when (labelChips.checkedChipId) {
        R.id.chipHome -> getString(R.string.address_label_home)
        R.id.chipWork -> getString(R.string.address_label_work)
        else -> customLabelInput.text?.toString().orEmpty()
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    /** Centres the map on the device position: the newest last-known fix, otherwise one fresh fix. */
    @SuppressLint("MissingPermission")
    private fun moveToCurrentLocation(showErrors: Boolean) {
        val manager = requireContext().getSystemService<LocationManager>()
        val providers = manager?.getProviders(true).orEmpty().filter { it != LocationManager.PASSIVE_PROVIDER }
        if (manager == null || providers.isEmpty() || !LocationManagerCompat.isLocationEnabled(manager)) {
            if (showErrors) showMessage(R.string.pick_location_unavailable)
            return
        }
        // A provider can refuse (e.g. GPS with only the approximate permission), hence runCatching.
        val lastKnown = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null) {
            showOnMap(lastKnown)
            return
        }
        val provider = if (LocationManager.NETWORK_PROVIDER in providers) LocationManager.NETWORK_PROVIDER else providers.first()
        val request = CancellationSignal().also { locationRequest = it }
        runCatching {
            LocationManagerCompat.getCurrentLocation(
                manager, provider, request, ContextCompat.getMainExecutor(requireContext()),
            ) { location ->
                if (location != null) {
                    showOnMap(location)
                } else if (showErrors) {
                    showMessage(R.string.pick_location_unavailable)
                }
            }
        }.onFailure { if (showErrors) showMessage(R.string.pick_location_unavailable) }
    }

    private fun showOnMap(location: Location) {
        mapView?.controller?.animateTo(GeoPoint(location.latitude, location.longitude))
    }

    private fun showMessage(messageRes: Int) {
        val view = view ?: return
        Snackbar.make(view, messageRes, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        // Đà Nẵng city centre, used until the user's position is known.
        const val DEFAULT_LATITUDE = 16.0544
        const val DEFAULT_LONGITUDE = 108.2022
        const val DEFAULT_ZOOM = 17.0
    }
}

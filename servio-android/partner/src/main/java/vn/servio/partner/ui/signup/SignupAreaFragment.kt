package vn.servio.partner.ui.signup

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.databinding.FragmentSignupAreaBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.map.MapConfig
import vn.servio.partner.ui.signup.SignupAreaViewModel.Event
import vn.servio.partner.ui.signup.SignupAreaViewModel.MapCamera
import javax.inject.Inject

/**
 * PS-04 Đăng ký – Bước 3/4 Khu vực nhận việc. The anchor is the map centre (pin fixed in the middle).
 * With `manage = true` it is the "Khu vực nhận việc" screen opened from PS-23 (no step bar, "Lưu" goes back).
 */
@AndroidEntryPoint
class SignupAreaFragment : Fragment(R.layout.fragment_signup_area) {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var deviceLocation: DeviceLocation

    private val viewModel: SignupAreaViewModel by viewModels()
    private var mapView: MapView? = null

    private val manage: Boolean get() = arguments?.getBoolean(SignupSkillsFragment.ARG_MANAGE) == true

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) viewModel.locate(showErrors = true) else showMessage(getString(R.string.location_permission_denied))
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSignupAreaBinding.bind(view)
        if (manage) {
            binding.toolbar.setTitle(R.string.area_manage_title)
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.setNavigationContentDescription(R.string.action_back)
            binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
            binding.steps.root.isVisible = false
            binding.actionBar.primaryButton.setText(R.string.action_save)
        } else {
            setupSignupToolbar(binding.toolbar, authRepository)
            SignupSteps.bind(binding.steps, 3, R.string.signup_step_area)
        }

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
            if (deviceLocation.hasPermission) viewModel.locate(showErrors = true) else locationPermission.launch(DeviceLocation.PERMISSIONS)
        }
        binding.radiusChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val index = listOf(R.id.radius3, R.id.radius5, R.id.radius10, R.id.radius20).indexOf(checkedIds.firstOrNull())
            if (index >= 0) viewModel.radiusKm = SignupAreaViewModel.RADII[index]
        }
        binding.actionBar.primaryButton.setOnClickListener {
            val center = binding.map.mapCenter
            viewModel.save(center.latitude, center.longitude)
        }

        collectWhenStarted(viewModel.state) { state ->
            LoadStatus.bind(binding.status, state, binding.form) { viewModel.load() }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content && !viewModel.isFormFilled) {
                viewModel.isFormFilled = true
                val me = state.data
                val chipId = when (viewModel.radiusKm) {
                    3 -> R.id.radius3
                    5 -> R.id.radius5
                    20 -> R.id.radius20
                    else -> R.id.radius10
                }
                binding.radiusChips.check(chipId)
                if (me.anchorLatitude != null && me.anchorLongitude != null && viewModel.camera == null) {
                    binding.map.controller.setCenter(GeoPoint(me.anchorLatitude, me.anchorLongitude))
                } else if (!me.hasAnchor && deviceLocation.hasPermission) {
                    viewModel.locate(showErrors = false)
                }
            }
        }
        collectWhenStarted(viewModel.saving) { saving -> binding.actionBar.primaryButton.isEnabled = !saving }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                Event.Saved -> if (manage) findNavController().navigateUp() else openSignupStep(R.id.ps05)
                is Event.Error -> showMessage(event.message)
                is Event.MoveTo -> mapView?.controller?.animateTo(GeoPoint(event.fix.latitude, event.fix.longitude))
                Event.LocationUnavailable -> showMessage(getString(R.string.location_unavailable))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.let { viewModel.camera = MapCamera(it.mapCenter.latitude, it.mapCenter.longitude, it.zoomLevelDouble) }
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        mapView?.onDetach()
        mapView = null
        super.onDestroyView()
    }

    private fun showMessage(text: String) {
        val view = view ?: return
        Snackbar.make(view, text, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        // Đà Nẵng city centre, used until the anchor or the device position is known.
        const val DEFAULT_LATITUDE = 16.0544
        const val DEFAULT_LONGITUDE = 108.2022
        const val DEFAULT_ZOOM = 15.0
    }
}

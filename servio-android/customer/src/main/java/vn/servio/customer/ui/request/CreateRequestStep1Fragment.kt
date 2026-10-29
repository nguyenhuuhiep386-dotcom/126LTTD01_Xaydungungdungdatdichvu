package vn.servio.customer.ui.request

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import vn.servio.core.R as CoreR
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentCreateRequestStep1Binding
import vn.servio.customer.ui.category.CategoryListFragment
import vn.servio.customer.ui.category.CategoryListViewModel
import vn.servio.customer.ui.common.CategoryIcons
import vn.servio.customer.ui.request.CreateRequestViewModel.FieldError

/**
 * CS-08 Tạo yêu cầu – Bước 1 Mô tả: service (with "Đổi" through CS-07 in pick mode), title, description and up to
 * 6 photos uploaded right after picking. Shares [CreateRequestViewModel] with CS-09 → CS-11.
 */
@AndroidEntryPoint
class CreateRequestStep1Fragment : Fragment(R.layout.fragment_create_request_step1) {

    private val viewModel: CreateRequestViewModel by navGraphViewModels(R.id.create_request_graph) {
        defaultViewModelProviderFactory
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        viewModel.onCaptureResult(saved)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else showMessage(R.string.create_request_camera_denied)
    }

    private val pickOne = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addImages(listOf(uri))
    }

    /** PickMultipleVisualMedia takes its limit at registration (and needs at least 2), so one launcher per limit. */
    private val pickMany = (2..RequestDraft.MAX_IMAGES).associateWith { limit ->
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(limit)) { uris ->
            viewModel.addImages(uris)
        }
    }

    private var binding: FragmentCreateRequestStep1Binding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCreateRequestStep1Binding.bind(view)
        this.binding = binding
        CreateRequestSteps.bind(binding.steps, step = 1, label = R.string.create_request_step_describe)

        binding.toolbar.setNavigationOnClickListener { confirmLeave() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { confirmLeave() }

        // The ViewModel is the only source of the text (rotation and process death included).
        val draft = viewModel.draft.value
        binding.titleInput.setText(draft.title)
        binding.descriptionInput.setText(draft.description)
        binding.titleInput.doAfterTextChanged { viewModel.setTitle(it?.toString().orEmpty()) }
        binding.descriptionInput.doAfterTextChanged { viewModel.setDescription(it?.toString().orEmpty()) }

        val adapter = DraftImageAdapter(
            thumbnails = ImageThumbnails(requireContext().contentResolver, viewLifecycleOwner.lifecycleScope, THUMBNAIL_EDGE),
            onAdd = { AddImageSheet.show(childFragmentManager, showCamera = hasCamera()) },
            onRetry = { viewModel.retryImage(it.localId) },
            onRemove = { viewModel.removeImage(it.localId) },
        )
        binding.imageList.adapter = adapter
        childFragmentManager.setFragmentResultListener(AddImageSheet.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            when (result.getString(AddImageSheet.RESULT_SOURCE)) {
                AddImageSheet.Source.CAMERA.name -> openCamera()
                AddImageSheet.Source.GALLERY.name -> openGallery()
            }
        }

        binding.changeServiceButton.setOnClickListener { changeService() }
        observeServiceResult()
        binding.continueButton.setOnClickListener { onContinue() }

        collectWhenStarted(viewModel.category) { state -> binding.bindCategory(state) }
        collectWhenStarted(viewModel.draft.map { it.images }.distinctUntilChanged()) { images ->
            adapter.submitImages(images)
            binding.imagesLabel.text = getString(R.string.create_request_images_label, images.size, RequestDraft.MAX_IMAGES)
        }
        collectWhenStarted(viewModel.fieldErrors) { errors -> binding.bindErrors(errors) }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // region Service

    private fun FragmentCreateRequestStep1Binding.bindCategory(state: UiState<CategoryChoice>) {
        serviceGroup.isVisible = state is UiState.Content
        when (state) {
            UiState.Loading -> {
                serviceIcon.setImageResource(R.drawable.ic_category_other)
                serviceName.text = getString(R.string.create_request_service_loading)
                changeServiceButton.setText(R.string.create_request_change_service)
                changeServiceButton.isEnabled = false
            }
            is UiState.Error -> {
                serviceIcon.setImageResource(R.drawable.ic_category_other)
                serviceName.text = state.message
                changeServiceButton.setText(CoreR.string.action_retry)
                changeServiceButton.isEnabled = true
            }
            is UiState.Content -> {
                serviceIcon.setImageResource(CategoryIcons.of(state.data.group))
                serviceName.text = state.data.service.name
                serviceGroup.text = state.data.group.name
                changeServiceButton.setText(R.string.create_request_change_service)
                changeServiceButton.isEnabled = true
            }
        }
    }

    /** "Đổi": CS-07 in pick mode, opened on the current group. "Thử lại" when the service could not be loaded. */
    private fun changeService() {
        when (val state = viewModel.category.value) {
            UiState.Loading -> Unit
            is UiState.Error -> viewModel.loadCategory()
            is UiState.Content -> {
                val navController = findNavController()
                if (navController.currentDestination?.id != R.id.cs08) return
                navController.navigate(
                    R.id.cs07,
                    bundleOf(
                        CategoryListViewModel.ARG_CATEGORY_ID to state.data.group.id,
                        CategoryListFragment.ARG_PICK_RESULT to true,
                    ),
                )
            }
        }
    }

    /** The service picked on CS-07; the key is removed once applied so a recreated view does not apply it again. */
    private fun observeServiceResult() {
        val handle = findNavController().getBackStackEntry(R.id.cs08).savedStateHandle
        handle.getLiveData<String>(CategoryListFragment.RESULT_SERVICE_ID).observe(viewLifecycleOwner) { id ->
            if (id == null) return@observe
            handle.remove<String>(CategoryListFragment.RESULT_SERVICE_ID)
            viewModel.changeCategory(id)
        }
    }

    // endregion

    // region Images

    private fun hasCamera() = requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun openGallery() {
        val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
        when (val free = viewModel.freeImageSlots) {
            0 -> Unit
            1 -> pickOne.launch(request)
            else -> pickMany.getValue(free).launch(request)
        }
    }

    /**
     * CAMERA is not declared, so TakePicture needs no runtime permission. Should a library ever add it to the merged
     * manifest, the camera intent would be refused without the grant: ask first in that case.
     */
    private fun openCamera() {
        if (viewModel.freeImageSlots <= 0) return
        if (declaresCameraPermission() &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            launchCamera()
        }
    }

    private fun declaresCameraPermission(): Boolean {
        val context = requireContext()
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull()
        return info?.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }

    private fun launchCamera() {
        val uri = viewModel.startCapture()
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            viewModel.onCaptureResult(false)
            showMessage(R.string.create_request_camera_unavailable)
        }
    }

    // endregion

    // region Validation

    private fun FragmentCreateRequestStep1Binding.bindErrors(errors: Set<FieldError>) {
        titleLayout.error = when {
            FieldError.TITLE_LENGTH in errors -> getString(R.string.create_request_title_length)
            FieldError.TITLE_CONTACT_INFO in errors -> getString(R.string.create_request_contact_info)
            else -> null
        }
        descriptionLayout.error = when {
            FieldError.DESCRIPTION_LENGTH in errors -> getString(R.string.create_request_description_length)
            FieldError.DESCRIPTION_CONTACT_INFO in errors -> getString(R.string.create_request_contact_info)
            else -> null
        }
        val imageError = imageErrorMessage(errors)
        imagesHelper.text = getString(imageError ?: R.string.create_request_images_helper)
        imagesHelper.setTextColor(
            ContextCompat.getColor(requireContext(), if (imageError != null) CoreR.color.servio_error else CoreR.color.servio_text_secondary),
        )
    }

    private fun imageErrorMessage(errors: Set<FieldError>): Int? = when {
        FieldError.IMAGES_UPLOADING in errors -> R.string.create_request_images_uploading
        FieldError.IMAGES_FAILED in errors -> R.string.create_request_images_failed
        else -> null
    }

    private fun onContinue() {
        val binding = binding ?: return
        if (viewModel.category.value !is UiState.Content) {
            showMessage(R.string.create_request_service_missing)
            return
        }
        if (viewModel.validateDescribe()) {
            val navController = findNavController()
            if (navController.currentDestination?.id == R.id.cs08) navController.navigate(R.id.cs09)
            return
        }
        val errors = viewModel.fieldErrors.value
        val target = when {
            FieldError.TITLE_LENGTH in errors || FieldError.TITLE_CONTACT_INFO in errors -> binding.titleLabel
            FieldError.DESCRIPTION_LENGTH in errors || FieldError.DESCRIPTION_CONTACT_INFO in errors -> binding.descriptionLabel
            else -> binding.imagesLabel
        }
        binding.scroll.post { scrollTo(binding, target) }
        imageErrorMessage(errors)?.let(::showMessage)
    }

    private fun scrollTo(binding: FragmentCreateRequestStep1Binding, target: View) {
        val rect = Rect()
        target.getDrawingRect(rect)
        binding.scroll.offsetDescendantRectToMyCoords(target, rect)
        val top = rect.top - resources.getDimensionPixelSize(CoreR.dimen.space_l)
        binding.scroll.smoothScrollTo(0, top.coerceAtLeast(0))
    }

    // endregion

    /** Back (toolbar or system): ask first when something was typed or picked. */
    private fun confirmLeave() {
        if (!viewModel.hasDescribeContent) {
            leave()
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog_Destructive)
            .setTitle(R.string.create_request_discard_title)
            .setMessage(R.string.create_request_discard_message)
            .setNegativeButton(R.string.create_request_discard_keep, null)
            .setPositiveButton(R.string.create_request_discard_confirm) { _, _ -> leave() }
            .show()
    }

    /** Pops the whole flow; the ViewModel is cleared and deletes the camera files. */
    private fun leave() {
        findNavController().popBackStack(R.id.create_request_graph, true)
    }

    private fun showMessage(messageRes: Int) {
        val binding = binding ?: return
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).setAnchorView(binding.continueButton).show()
    }

    private companion object {
        /** Long edge of a decoded thumbnail; a tile is about a third of the screen width. */
        const val THUMBNAIL_EDGE = 480
    }
}

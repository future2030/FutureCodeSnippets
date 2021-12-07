package com.example.app.camerax.barcode_scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.example.app.databinding.FragmentCameraWithTrailerInfoBinding
import dagger.android.support.AndroidSupportInjection
import timber.log.Timber
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BarcodeScanWithCameraXFragment : Fragment() {

    companion object {

        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )

        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

    }


    private var requestType: BarcodeTypes = BarcodeTypes.FORMAT_ALL_FORMATS

    private lateinit var _binding: FragmentCameraWithTrailerInfoBinding
    val binding get() = _binding
    private val executor: Executor by lazy { Executors.newSingleThreadExecutor() }
    lateinit var ctx: Context

    private var cameraProvider: ProcessCameraProvider? = null
    lateinit var cameraSelector: CameraSelector
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null


    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var viewModel: BarcodeScanWithCameraXFragmentViewModel


    private val screenAspectRatio: Int
        get() {
            val metrics = DisplayMetrics().also { binding.viewFinder.display.getRealMetrics(it) }
            return aspectRatio(metrics.widthPixels, metrics.heightPixels)
        }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraWithTrailerInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
        ctx = context
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel =
            ViewModelProvider(this, viewModelFactory).get(BarcodeScanWithCameraXFragmentViewModel::class.java)
        initUI()
        setClickListeners()
        requestPermissions()
    }

    private fun initUI() {

        viewModel.scannedData.observe(viewLifecycleOwner, {
            binding.tvHintTextDesc.text = it
        })


    }

    private fun setClickListeners() {
        binding.btnCapture.setOnClickListener {
            viewModel.setOnComplete(true)
            handleBackAction()
        }
        binding.backButton.setOnClickListener {
            handleBackAction()
        }
    }



    private fun requestPermissions() {
        if (allPermissionsGranted()) {
            binding.viewFinder.post { setupCamera() }
        } else {
            requestPermissions(
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                binding.viewFinder.post { setupCamera() }
            } else {
                findNavController().popBackStack()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            context as Activity,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }


    private fun setupCamera() {

        binding.viewFinder.visibility = View.VISIBLE
        cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val cameraProviderFuture = context?.let { ProcessCameraProvider.getInstance(it) }

        //execute while orientation changes
        cameraProviderFuture?.addListener(
            {
                try {
                    // Used to bind the lifecycle of cameras to the lifecycle owner
                    cameraProvider = cameraProviderFuture.get()
                    bindCameraUseCases()

                } catch (exc: Exception) {
                    if (activity == null) {
                        Timber.v(exc)
                    } else {
                        Timber.w(exc)
                    }
                }

            }, ContextCompat.getMainExecutor(context)
        )

    }

    private fun bindCameraUseCases() {
        bindPreviewUseCase()
        bindAnalyseUseCase()
    }


    private fun bindPreviewUseCase() {
        if (cameraProvider == null) {
            return
        }
        if (previewUseCase != null) {
            cameraProvider?.unbind(previewUseCase)
        }

        previewUseCase = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        previewUseCase?.setSurfaceProvider(binding.viewFinder.surfaceProvider)

        try {
            cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                previewUseCase
            )
        } catch (exc: Exception) {
            if (activity == null) {
                Timber.v(exc)
            } else {
                Timber.w(exc)
            }
        }
    }

    private fun bindAnalyseUseCase() {
        // Note that if you know which format of barcode your app is dealing with, detection will be
        // faster to specify the supported barcode formats one by one, e.g.
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(requestType.type)
            .build()

        val qrCodeScanner: BarcodeScanner = BarcodeScanning.getClient(options)

        if (cameraProvider == null) {
            return
        }
        if (analysisUseCase != null) {
            cameraProvider?.unbind(analysisUseCase)
        }

        analysisUseCase = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        analysisUseCase?.setAnalyzer(
            executor,
            { imageProxy ->
                processImageProxy(qrCodeScanner, imageProxy)
            }
        )

        try {
            cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                analysisUseCase
            )
        } catch (exc: Exception) {
            if (activity == null) {
                Timber.v(exc)
            } else {
                Timber.w(exc)
            }
        }
    }


    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(
        barcodeScanner: BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        imageProxy.image?.let { image ->


            val inputImage =
                InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { qrCodes ->

                    if (qrCodes.size > 0) {
                        qrCodes.forEach {
                            viewModel.setScannedValue(it.rawValue ?: "")
                            if (activity == null) {
                                Timber.v(it.rawValue)
                            } else {
                                Timber.w(it.rawValue)
                            }
                        }

                    } else {
                        viewModel.setScannedValue("")
                    }


                }
                .addOnFailureListener {
                    if (activity == null) {
                        Timber.v(it)
                    } else {
                        Timber.w(it)
                    }
                }.addOnCompleteListener {
                    // When the image is from CameraX analysis use case, must call image.close() on received
                    // images when finished using them. Otherwise, new images may not be received or the camera
                    // may stall.
                    imageProxy.close()
                }
        }
    }





    private fun handleBackAction() {
        findNavController().popBackStack()
    }


    /**
     *  [androidx.camera.core.ImageAnalysis], [androidx.camera.core.Preview] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    enum class BarcodeTypes(val type: Int) {
        FORMAT_UNKNOWN(Barcode.FORMAT_UNKNOWN),
        FORMAT_ALL_FORMATS(Barcode.FORMAT_ALL_FORMATS),
        FORMAT_CODE_128(Barcode.FORMAT_CODE_128),
        FORMAT_CODE_39(Barcode.FORMAT_CODE_39),
        FORMAT_CODE_93(Barcode.FORMAT_CODE_93),
        FORMAT_CODABAR(Barcode.FORMAT_CODABAR),
        FORMAT_DATA_MATRIX(Barcode.FORMAT_DATA_MATRIX),
        FORMAT_EAN_13(Barcode.FORMAT_EAN_13),
        FORMAT_EAN_8(Barcode.FORMAT_EAN_8),
        FORMAT_ITF(Barcode.FORMAT_ITF),
        FORMAT_QR_CODE(Barcode.FORMAT_QR_CODE),
        FORMAT_UPC_A(Barcode.FORMAT_UPC_A),
        FORMAT_UPC_E(Barcode.FORMAT_UPC_E),
        FORMAT_PDF417(Barcode.FORMAT_PDF417),
        FORMAT_AZTEC(Barcode.FORMAT_AZTEC),
        TYPE_UNKNOWN(Barcode.TYPE_UNKNOWN),
        TYPE_CONTACT_INFO(Barcode.TYPE_CONTACT_INFO),
        TYPE_EMAIL(Barcode.TYPE_EMAIL),
        TYPE_ISBN(Barcode.TYPE_ISBN),
        TYPE_PHONE(Barcode.TYPE_PHONE),
        TYPE_PRODUCT(Barcode.TYPE_PRODUCT),
        TYPE_SMS(Barcode.TYPE_SMS),
        TYPE_TEXT(Barcode.TYPE_TEXT),
        TYPE_URL(Barcode.TYPE_URL),
        TYPE_WIFI(Barcode.TYPE_WIFI),
        TYPE_GEO(Barcode.TYPE_GEO),
        TYPE_CALENDAR_EVENT(Barcode.TYPE_CALENDAR_EVENT),
        TYPE_DRIVER_LICENSE(Barcode.TYPE_DRIVER_LICENSE);


        companion object {
            private val map = values().associateBy(BarcodeTypes::type)
            fun fromInt(type: Int) = map[type]
        }

    }


}
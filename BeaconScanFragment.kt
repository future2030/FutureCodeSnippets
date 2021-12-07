package com.example.app.main.bluetooth_beacon.fragment


import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.app.R
import com.example.app.dagger.AppExecutors
import com.example.app.databinding.FragmentBeaconScanBinding
import com.example.app.main.bluetooth_beacon.BeaconModel
import com.example.app.main.bluetooth_beacon.BeaconScanActivityViewModel
import com.example.app.main.bluetooth_beacon.adapter.BluetoothDeviceListAdapter
import com.example.app.manager.ConfigManager
import com.example.app.util.*
import dagger.android.support.AndroidSupportInjection
import org.altbeacon.beacon.*
import timber.log.Timber
import java.util.*
import javax.inject.Inject


class BeaconScanFragment : Fragment() {
    private lateinit var ctx: Context
    private lateinit var lBinder: FragmentBeaconScanBinding

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var configManager: ConfigManager

    @Inject
    lateinit var appExecutors: AppExecutors

    lateinit var viewModel: BeaconScanActivityViewModel
    var beaconManager: BeaconManager? = null

    private var timer: Timer? = null

    //DjTodo: confirm this delay
    private val apiCallDelayInSec = 5L
    var durationSec: Long = 0L
    var bleDurationLimit: Long = 0L
    private lateinit var trailerListAdapter: BluetoothDeviceListAdapter


    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        )
        { permissions ->
            if (activity == null) {
                return@registerForActivityResult
            }
            var granted = true
            permissions.entries.forEach {
                if (it.value.not()) {
                    granted = false
                }
            }
            lBinder.llPermissions.isGone = granted
            lBinder.clScanningContainer.isVisible = granted

            if (granted) {
                setupBluetoothBeaconScan()
            } else {
                showToast(getString(R.string.please_enable_permissions_to_continue))

            }

        }

    private val rangingObserver = Observer<Collection<Beacon>> { beacons ->
        Timber.d("beacon: Ranged: ${beacons.count()} beacons")
        for (beacon: Beacon in beacons) {
            //DjTodo: we may need to use custom ble layout
            if (beacon.id1.toString() == Constants.BLUETOOTH_BEACON_LAYOUT) {
                val beaconModel =
                    BeaconModel(
                        majorId = beacon.id2.toString(),
                        minorId = beacon.id3.toString()
                    )
                viewModel.beaconMap[beaconModel.beaconTempId] = beaconModel
                Timber.d("beacon: $beacon about ${beacon.distance} meters away")
            }
        }
    }

    private fun setupBluetoothBeaconScan() {
        try {
            if (beaconManager?.checkAvailability()?.not() == false) {
                val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (mBluetoothAdapter.isEnabled.not()) {
                    mBluetoothAdapter.enable()
                }
            }
        } catch (e: Exception) {
            showToast(getString(R.string.bluetooth_le_not_supported_by_this_device))
            findNavController().popBackStack()
            return
        }
        startTimer()
        beaconManager?.startRangingBeacons(getRegionRequest())
        showScannerUi()
    }

    private fun getRegionRequest(): Region {
        return Region(
            "all-beacons-region",
            Identifier.parse(configManager.bleBeaconUUID),
            null,
            null
        )
    }

    override fun onStart() {
        super.onStart()
        requestBeaconPermissions()
    }

    override fun onStop() {
        super.onStop()
        stopBeaconScanner()
    }

    private fun stopBeaconScanner() {
        beaconManager?.stopRangingBeacons(getRegionRequest())
        stopTimer()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        AndroidSupportInjection.inject(this)
        ctx = context
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        lBinder = DataBindingUtil.inflate(
            inflater, R.layout.fragment_beacon_scan,
            container, false
        )
        return lBinder.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.let {
            viewModel =
                ViewModelProvider(
                    it,
                    viewModelFactory
                ).get(BeaconScanActivityViewModel::class.java)
        }
        setListeners()
        initValues()

    }

    private fun initValues() {
        lBinder.rvDeviceList.layoutManager = LinearLayoutManager(context)
        trailerListAdapter = BluetoothDeviceListAdapter(
            arrayListOf(),
            object : EventListeners.OnRecycleViewClickListener {
                override fun onClick(position: Int) {
                    //DjTodo: replace this adapter with actual adapter
                }

                override fun onClick() {

                }


            })
        lBinder.rvDeviceList.adapter = trailerListAdapter
        bleDurationLimit = configManager.bleScanDurationInSec
        beaconManager = BeaconManager.getInstanceForApplication(ctx)
        beaconManager?.beaconParsers?.add(BeaconParser().setBeaconLayout(Constants.BLUETOOTH_BEACON_LAYOUT))
        // Set up a Live Data observer so this Activity can get ranging callbacks
        // observer will be called each time the monitored regionState changes (inside vs. outside region)
        beaconManager?.getRegionViewModel(getRegionRequest())?.rangedBeacons?.observe(
            viewLifecycleOwner,
            rangingObserver
        )
        viewModel.trailerList.observe(
            viewLifecycleOwner,
            {
                trailerListAdapter.submitList(it)

            }
        )
    }

    private fun requestBeaconPermissions() {
        when {
            hasPermissions(ctx, getBluetoothBeaconPermissionList()) -> {
                lBinder.llPermissions.isGone = true
                lBinder.clScanningContainer.isVisible = true
                setupBluetoothBeaconScan()
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                requestPermissionLauncher.launch(
                    getBluetoothBeaconPermissionList().toTypedArray()
                )
            }
        }
    }

    private fun setListeners() {
        lBinder.ivBack.setOnSingleClickListener {
            activity?.onBackPressed()
        }

        lBinder.llPermissions.setOnSingleClickListener {
            val intent = Intent()
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            val uri: Uri = Uri.fromParts("package", activity?.packageName, null)
            intent.data = uri
            context?.startActivity(intent)
        }


    }

    private fun startTimer() {
        durationSec = 0L
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                appExecutors.mainThread().execute {
                    durationSec += 1L
                    if ((durationSec % apiCallDelayInSec) == 0L) {
                        viewModel.checkScannedBeacons()
                    }
                    lBinder.llScanningDescription.isVisible =
                        durationSec >= bleDurationLimit / 2 && viewModel.trailerList.value?.isNullOrEmpty() == true
                    if (durationSec >= bleDurationLimit || viewModel.trailerList.value?.isNullOrEmpty()
                            ?.not() == true
                    ) {
                        showTrailerListUi()
                    }
                }

            }
        }, 1000L, 1000L)
    }

    private fun showScannerUi() {
        lBinder.rvDeviceList.isVisible = false
        lBinder.ivScanningLarge.isVisible = true

    }

    private fun showTrailerListUi() {
        lBinder.rvDeviceList.isVisible = true
        lBinder.ivScanningLarge.isVisible = false
        trailerListAdapter.submitList(viewModel.trailerList.value ?: arrayListOf())
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun updateValuesToUi() {


    }


}


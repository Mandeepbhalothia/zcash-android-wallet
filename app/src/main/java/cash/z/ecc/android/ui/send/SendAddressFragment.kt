package cash.z.ecc.android.ui.send

import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import cash.z.ecc.android.R
import cash.z.ecc.android.databinding.FragmentSendAddressBinding
import cash.z.ecc.android.di.viewmodel.activityViewModel
import cash.z.ecc.android.ext.*
import cash.z.ecc.android.ui.base.BaseFragment
import cash.z.wallet.sdk.Synchronizer
import cash.z.wallet.sdk.block.CompactBlockProcessor.WalletBalance
import cash.z.wallet.sdk.ext.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SendAddressFragment : BaseFragment<FragmentSendAddressBinding>(),
    ClipboardManager.OnPrimaryClipChangedListener {

    private var maxZatoshi: Long? = null

    val sendViewModel: SendViewModel by activityViewModel()

    override fun inflate(inflater: LayoutInflater): FragmentSendAddressBinding =
        FragmentSendAddressBinding.inflate(inflater)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.backButtonHitArea.onClickNavTo(R.id.action_nav_send_address_to_nav_home)
        binding.buttonNext.setOnClickListener {
            onSubmit()
        }
        binding.textBannerAction.setOnClickListener {
            onPaste()
        }
        binding.textBannerMessage.setOnClickListener {
            onPaste()
        }
        binding.textMax.setOnClickListener {
            onMax()
        }

        // Apply View Model
        if (sendViewModel.zatoshiAmount > 0L) {
            sendViewModel.zatoshiAmount.convertZatoshiToZecString(8).let { amount ->
                binding.inputZcashAmount.setText(amount)
            }
        } else {
            binding.inputZcashAmount.setText(null)
        }
        if (!sendViewModel.toAddress.isNullOrEmpty()) {
            binding.inputZcashAddress.setText(sendViewModel.toAddress)
        } else {
            binding.inputZcashAddress.setText(null)
        }

        binding.inputZcashAddress.onEditorActionDone(::onSubmit)
        binding.inputZcashAmount.onEditorActionDone(::onSubmit)

        binding.inputZcashAddress.apply {
            doAfterTextChanged {
                onAddressChanged(text.toString())
            }
        }

        binding.textLayoutAddress.setEndIconOnClickListener {
            mainActivity?.maybeOpenScan()
        }
    }

    private fun onAddressChanged(address: String) {
        resumedScope.launch {
            var type = when (sendViewModel.validateAddress(address)) {
                is Synchronizer.AddressType.Transparent -> "This is a valid transparent address" to R.color.zcashGreen
                is Synchronizer.AddressType.Shielded -> "This is a valid shielded address" to R.color.zcashGreen
                is Synchronizer.AddressType.Invalid -> "This address appears to be invalid" to R.color.zcashRed
            }
            if (address == sendViewModel.synchronizer.getAddress()) type =
                "Warning, this appears to be your address!" to R.color.zcashRed
            binding.textLayoutAddress.helperText = type.first
            binding.textLayoutAddress.setHelperTextColor(ColorStateList.valueOf(type.second.toAppColor()))
        }
    }


    private fun onSubmit(unused: EditText? = null) {
        sendViewModel.toAddress = binding.inputZcashAddress.text.toString()
        binding.inputZcashAmount.convertZecToZatoshi()?.let { sendViewModel.zatoshiAmount = it }
        sendViewModel.validate(maxZatoshi).onFirstWith(resumedScope) {
            if (it == null) {
                mainActivity?.navController?.navigate(R.id.action_nav_send_address_to_send_memo)
            } else {
                resumedScope.launch {
                    binding.textAddressError.text = it
                    delay(1500L)
                    binding.textAddressError.text = ""
                }
            }
        }
    }

    private fun onMax() {
        if (maxZatoshi != null) {
            binding.inputZcashAmount.apply {
                setText(maxZatoshi.convertZatoshiToZecString())
                postDelayed({
                    requestFocus()
                    setSelection(text?.length ?: 0)
                }, 10L)
            }
        }
    }


    override fun onAttach(context: Context) {
        super.onAttach(context)
        mainActivity?.clipboard?.addPrimaryClipChangedListener(this)
    }

    override fun onDetach() {
        super.onDetach()
        mainActivity?.clipboard?.removePrimaryClipChangedListener(this)
    }

    override fun onResume() {
        super.onResume()
        updateClipboardBanner()
        sendViewModel.synchronizer.balances.collectWith(resumedScope) {
            onBalanceUpdated(it)
        }
        binding.inputZcashAddress.text.toString().let {
            if (!it.isNullOrEmpty()) onAddressChanged(it)
        }
    }

    private fun onBalanceUpdated(balance: WalletBalance) {
        binding.textLayoutAmount.helperText =
            "You have ${balance.availableZatoshi.convertZatoshiToZecString(8)} available"
        maxZatoshi = balance.availableZatoshi - ZcashSdk.MINERS_FEE_ZATOSHI
    }

    override fun onPrimaryClipChanged() {
        twig("clipboard changed!")
        updateClipboardBanner()
    }

    private fun updateClipboardBanner() {
        binding.groupBanner.goneIf(loadAddressFromClipboard() == null)
    }

    private fun onPaste() {
        mainActivity?.clipboard?.let { clipboard ->
            if (clipboard.hasPrimaryClip()) {
                binding.inputZcashAddress.setText(clipboard.text())
            }
        }
    }

    private fun loadAddressFromClipboard(): String? {
        mainActivity?.clipboard?.apply {
            if (hasPrimaryClip()) {
                text()?.let { text ->
                    if (text.startsWith("zs") && text.length > 70) {
                        return@loadAddressFromClipboard text.toString()
                    }
                    // treat t-addrs differently in the future
                    if (text.startsWith("t1") && text.length > 32) {
                        return@loadAddressFromClipboard text.toString()
                    }
                }
            }
        }
        return null
    }

    private fun ClipboardManager.text(): CharSequence =
        primaryClip!!.getItemAt(0).coerceToText(mainActivity)
}
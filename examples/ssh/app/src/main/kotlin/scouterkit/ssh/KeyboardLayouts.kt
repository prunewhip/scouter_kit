package scouterkit.ssh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Offers Android the physical keyboard layouts in res/xml/keyboard_layouts.xml (the manifest's meta-data says
// which): Android's input manager asks this receiver for them and lists them under Physical keyboard. Nothing to do
// when it is asked.
class KeyboardLayouts : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}

package net.namekdev.moments;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import org.xmlpull.v1.XmlSerializer;
import java.io.StringWriter;

/** Snapshot for ADB tests. Unlike `uiautomator dump`, this needs no idle second between timer updates. */
public class UiProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(info);
            // Let window transitions settle, without waiting through a whole timer second.
            getUiAutomation().waitForIdle(200, 5_000);
            AccessibilityNodeInfo root = null;
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (root == null && SystemClock.elapsedRealtime() < deadline) {
                root = getUiAutomation().getRootInActiveWindow();
                if (root == null) SystemClock.sleep(100);
            }
            if (root == null) throw new IllegalStateException("No active window");
            StringWriter text = new StringWriter();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(text);
            xml.startTag(null, "hierarchy");
            writeNode(xml, root);
            xml.endTag(null, "hierarchy");
            xml.flush();
            result.putString("hierarchy", text.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void writeNode(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.startTag(null, "node");
        xml.attribute(null, "resource-id", node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName());
        xml.attribute(null, "text", node.getText() == null ? "" : node.getText().toString());
        xml.attribute(null, "checked", Boolean.toString(node.isChecked()));
        xml.attribute(null, "enabled", Boolean.toString(node.isEnabled()));
        xml.attribute(null, "bounds", bounds.toShortString());
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) writeNode(xml, child);
        }
        xml.endTag(null, "node");
    }
}

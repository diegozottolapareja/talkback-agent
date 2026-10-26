package dev.tbagent.probe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Minimal app used only to find out whether this phone accepts sideloaded APKs. */
public class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText("Install probe OK: este telefono acepta APKs de prueba.");
        t.setTextSize(20f);
        t.setPadding(48, 96, 48, 48);
        setContentView(t);
    }
}

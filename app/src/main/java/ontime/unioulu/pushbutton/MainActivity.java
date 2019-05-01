package ontime.unioulu.pushbutton;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PUSHBUTTON";
    private static final int REQUEST_ENABLE_BT = 1;

    private CommunicationThread thread=null;
    private Handler handler;
    private boolean connected = false;

    private PowerManager.WakeLock wl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Force screen on
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "pushbutton:PowerTAG");
        wl.acquire();

        final ImageView image = findViewById(R.id.imageView);

        handler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case 100:
                        image.setImageResource(R.mipmap.hal9000_down);
                        connected = true;
                        return true;
                    //break;
                    case 101:
                        image.setImageResource(R.mipmap.hal9000_offline);
                        connected = false;
                        return true;
                    //break;
                    default:
                        Log.d(TAG, "Unhandled message.");
                        return false;
                }
                //return false;
            }
        });


        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.d("ACT", "No bluetooth adapters");
        }
        else if (!bluetoothAdapter.isEnabled()){
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        else {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired.size() > 0) {
                for (BluetoothDevice device: paired) {
                    Log.d("ACT", "DEVNAME: " + device.getName());
                    Log.d("ACT", "DEVADDR: " + device.getAddress());
                }
/*                if (bluetoothAdapter.startDiscovery()) {

                }*/
                thread = new CommunicationThread(bluetoothAdapter, handler);
                thread.start();
            }
            else Log.d("ACT", "No paired devices");
        }

        image.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        Log.d(TAG, "Image pressed down.");
                        if (connected) {
                            image.setImageResource(R.mipmap.hal9000_up);
                            if (thread != null) {
                                thread.write(1);
                            }
                        }
                        return true;
                    //break;
                    case MotionEvent.ACTION_UP:
                        Log.d(TAG, "Image press released.");
                        if (connected) {
                            image.setImageResource(R.mipmap.hal9000_down);
                            if (thread != null) {
                                thread.write(0);
                            }
                        }
                        return true;
                    //break;
                    default:
                        Log.d(TAG, "Unhandled event.");
                        return false;
                    //break;
                }
                //return true;
            }
        });
    }

    @Override
    protected void onDestroy() {
        // Release wakelock
        wl.release();
        // Close listening socket
        thread.disconnect();
        // Wait thread to end.
        try {
            thread.join();
        }
        catch (InterruptedException ie) {
            Log.d(TAG, "Joining thread interrupted.");
        }
        Log.d(TAG, "State cleaned.");
        super.onDestroy();
    }
}

class CommunicationThread extends Thread {
    private static final String TAG = "COMMUNICATIONTHREAD";
    private BluetoothServerSocket srvSck = null;
    private BluetoothSocket sck = null;
    private InputStream in;
    private OutputStream out;

    private Handler handler;

    private volatile boolean running = true;

    public CommunicationThread(BluetoothAdapter adapter, Handler handler) {

        String uuidStr = "080aefda-617e-4972-a257-a6cfa0e1c33b";
        try {
            srvSck = adapter.listenUsingRfcommWithServiceRecord("test", UUID.fromString(uuidStr));
        }
        catch (IOException ioe) {
            Log.d(TAG, "Starting to listen failed.");
            return;
        }

        this.handler = handler;
    }

    public void disconnect() {
        running = false;

        try {
            srvSck.close();
        } catch (IOException ioe) {
            Log.d(TAG, "Close() failed.");
        }
        catch (NullPointerException npe) {
            Log.d(TAG, "No listening socket to close().");
        }

        try {
            sck.close();
        }
        catch (IOException ioe) {
            Log.d(TAG, "comm-socket close() failed.");
        }
        catch (NullPointerException npe) {
            Log.d(TAG, "No comm-socked to close().");
        }
    }

    public void write(int value) {
        try {
            out.write(value);
        }
        catch (IOException ioe) {
            Log.d(TAG, "Write failed.");
        }
        catch (NullPointerException npe) {
            Log.d(TAG, "No output stream.");
        }
    }

    @Override
    public void run() {


        if (srvSck != null) {
            while(running) {
                Log.d(TAG, "Starting accept()...");
                try {
                    sck = srvSck.accept();
                } catch (IOException ioe) {
                    Log.d(TAG, "Accept() failed.");
                    return;
                }
                Message msg = new Message();
                msg.what = 100;
                handler.sendMessage(msg);
                Log.d(TAG, "Accepted connection.");

                if (sck != null) {

                    try {
                        in = sck.getInputStream();
                        out = sck.getOutputStream();
                    } catch (IOException ioe) {
                        Log.d(TAG, "Couldn't get stream from socket.");
                        msg.what = 101;
                        handler.sendMessage(msg);
                        return;
                    }

                    int readReturnVal = 0;
                    byte[] buffer = new byte[1024];
                    while (readReturnVal != -1) {
                        try {
                            readReturnVal = in.read();
                        } catch (IOException ioe) {
                            Log.d(TAG, "Read() failed. Ending...");
                            break;
                        }
                    }
                    Log.d(TAG, "Reading socket ended.");

                    Message msg2 = new Message();
                    msg2.what = 101;
                    handler.sendMessage(msg2);
                    try {
                        sck.close();
                    } catch (IOException ioe) {
                        Log.d(TAG, "Closing socket failed.");
                    }
                }
            }
        }
    }
}
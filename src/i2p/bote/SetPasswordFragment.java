package i2p.bote;

import i2p.bote.util.RobustAsyncTask;
import i2p.bote.util.TaskFragment;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class SetPasswordFragment extends Fragment {
    private Callbacks mCallbacks = sDummyCallbacks;

    public interface Callbacks {
        public void onTaskFinished();
    }
    private static Callbacks sDummyCallbacks = new Callbacks() {
        public void onTaskFinished() {};
    };

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (!(activity instanceof Callbacks))
            throw new IllegalStateException("Activity must implement fragment's callbacks.");
        mCallbacks = (Callbacks) activity;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = sDummyCallbacks;
    }

    // Code to identify the fragment that is calling onActivityResult().
    static final int PASSWORD_WAITER = 0;
    // Tag so we can find the task fragment again, in another
    // instance of this fragment after rotation.
    static final String PASSWORD_WAITER_TAG = "passwordWaiterTask";

    private FragmentManager mFM;
    Button mSubmit;
    TextView mError;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFM = getFragmentManager();
        PasswordWaiterFrag f = (PasswordWaiterFrag) mFM.findFragmentByTag(PASSWORD_WAITER_TAG);
        if (f != null)
            f.setTargetFragment(this, PASSWORD_WAITER);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_set_password, container);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final EditText oldField = (EditText) view.findViewById(R.id.password_old);
        final EditText newField = (EditText) view.findViewById(R.id.password_new);
        final EditText confirmField = (EditText) view.findViewById(R.id.password_confirm);
        mSubmit = (Button) view.findViewById(R.id.submit_password);
        mError = (TextView) view.findViewById(R.id.error);

        // If task is running, disable the submit button.
        PasswordWaiterFrag f = (PasswordWaiterFrag) mFM.findFragmentByTag(PASSWORD_WAITER_TAG);
        if (f != null) {
            mSubmit.setEnabled(false);
        }

        mSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String oldPassword = oldField.getText().toString();
                String newPassword = newField.getText().toString();
                String confirmNewPassword = confirmField.getText().toString();

                InputMethodManager imm = (InputMethodManager)getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(newField.getWindowToken(), 0);

                mSubmit.setEnabled(false);
                mError.setText("");

                PasswordWaiterFrag f = PasswordWaiterFrag.newInstance(oldPassword, newPassword, confirmNewPassword);
                f.setTask(new PasswordWaiter());
                f.setTargetFragment(SetPasswordFragment.this, PASSWORD_WAITER);
                mFM.beginTransaction()
                    .replace(R.id.password_waiter_frag, f, PASSWORD_WAITER_TAG)
                    .commit();
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PASSWORD_WAITER) {
            if (resultCode == Activity.RESULT_OK) {
                mCallbacks.onTaskFinished();
            } else if (resultCode == Activity.RESULT_CANCELED) {
                mSubmit.setEnabled(true);
                mError.setText(data.getStringExtra("error"));
            }
        }
    }

    public static class PasswordWaiterFrag extends TaskFragment<String, String, String> {
        String currentStatus;
        TextView mStatus;

        public static PasswordWaiterFrag newInstance(String... params) {
            PasswordWaiterFrag f = new PasswordWaiterFrag();
            Bundle args = new Bundle();
            args.putStringArray("params", params);
            f.setArguments(args);
            return f;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.dialog_status, container, false);
            mStatus = (TextView) v.findViewById(R.id.status);

            if (currentStatus != null && !currentStatus.isEmpty())
                mStatus.setText(currentStatus);

            return v;
        }

        @Override
        public String[] getParams() {
            Bundle args = getArguments();
            return args.getStringArray("params");
        }

        @Override
        public void updateProgress(String... values) {
            currentStatus = values[0];
            mStatus.setText(currentStatus);
        }

        @Override
        public void taskFinished(String result) {
            super.taskFinished(result);

            if (getTargetFragment() != null) {
                Intent i = new Intent();
                i.putExtra("result", result);
                getTargetFragment().onActivityResult(
                        getTargetRequestCode(), Activity.RESULT_OK, i);
            }
        }

        @Override
        public void taskCancelled(String error) {
            super.taskCancelled(error);

            if (getTargetFragment() != null) {
                Intent i = new Intent();
                i.putExtra("error", error);
                getTargetFragment().onActivityResult(
                        getTargetRequestCode(), Activity.RESULT_CANCELED, i);
            }
        }
    }

    private class PasswordWaiter extends RobustAsyncTask<String, String, String> {
        protected String doInBackground(String... params) {
            StatusListener lsnr = new StatusListener() {
                public void updateStatus(String status) {
                    publishProgress(status);
                }
            };
            try {
                I2PBote.getInstance().changePassword(
                        params[0].getBytes(),
                        params[1].getBytes(),
                        params[2].getBytes(),
                        lsnr);
                return null;
            } catch (Throwable e) {
                cancel(false);
                return e.getMessage();
            }
        }
    }
}

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/4/1.
 * 收件箱
 */
public class MailBox {

    private final ReentrantLock mMailBoxLock = new ReentrantLock();
    private final ArrayList<MailContent> mAllMails = new ArrayList<MailContent>();
    private final ArrayList<OnMailReceiveListener> mListeners = new ArrayList<OnMailReceiveListener>();

    @SuppressWarnings("unchecked")
    public void deliverMail(MailContent mail) {
        mail = mail.clone();
        mMailBoxLock.lock();
        try {
            mAllMails.add(mail);
            ArrayList<OnMailReceiveListener> listenerClone = (ArrayList<OnMailReceiveListener>) mListeners.clone();
            for (OnMailReceiveListener listener : listenerClone) {
                listener.onMailReceived(mail);
            }
        } finally {
            mMailBoxLock.unlock();
        }
    }

    public interface OnMailReceiveListener {
        void onMailReceived(MailContent mail);
    }

    public void registerListener(OnMailReceiveListener listener) {
        mListeners.add(listener);
    }

    @SuppressWarnings("unchecked")
    public ArrayList<MailContent> getAllMails() {
        mMailBoxLock.lock();
        try {
            return (ArrayList<MailContent>) mAllMails.clone();
        } finally {
            mMailBoxLock.unlock();
        }
    }
}

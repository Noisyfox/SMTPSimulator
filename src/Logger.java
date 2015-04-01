import javax.swing.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/3/31.
 */
public class Logger {

    private static Logger instance;

    public static void bindOutput(JTextArea textArea) {
        instance = new Logger(textArea);
    }

    public static Logger getInstance() {
        return instance;
    }

    private final String mLineSeparator;
    private final JTextArea mOutput;
    private final ReentrantLock mLock = new ReentrantLock();

    private Logger(JTextArea textArea) {
        mOutput = textArea;
        mLineSeparator = java.security.AccessController.doPrivileged(
                new sun.security.action.GetPropertyAction("line.separator"));
    }

    private void newLine() {
        print(mLineSeparator);
    }

    public Logger print(final String s) {
        Runnable ar = new Runnable() {
            @Override
            public void run() {
                mOutput.append(s);
            }
        };
        SwingUtilities.invokeLater(ar);
        return this;
    }

    public Logger println() {
        newLine();
        return this;
    }

    public Logger println(String s) {
        mLock.lock();
        try {
            print(s);
            newLine();
        } finally {
            mLock.unlock();
        }
        return this;
    }

    public Logger printf(String format, Object... args) {
        print(String.format(format, args));
        return this;
    }
}

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.ArrayList;

/**
 * Created by Noisyfox on 2015/3/31.
 */
public class MailingFrame implements MailBox.OnMailReceiveListener {
    private JFrame mainFrame;
    private JPanel MainForm;
    private JTextArea textArea_log;
    private JCheckBox checkBox_server_needAuth;
    private JTextField textField_server_userName;
    private JTextField textField_server_password;
    private JButton button_server_start;
    private JButton button_server_stop;
    private JList list_server_mail;
    private JTextField textField_server_from;
    private JTextField textField_server_to;
    private JTextArea textArea_server_mail;
    private JCheckBox checkBox_client_needAuth;
    private JTextField textField_client_userName;
    private JPasswordField passwordField_client_password;
    private JTextField textField_client_serverAddress;
    private JTextField textField_client_from;
    private JTextField textField_client_to;
    private JTextArea textArea_client_mail;
    private JButton button_client_send;
    private JButton button_client_connect;
    private JButton button_client_disconnect;
    private JButton button_client_next;
    private JTextField textField_client_subject;
    private JTextField textField_server_name;

    private DefaultListModel listModel_mail = new DefaultListModel();

    private MailBox mMailBox = new MailBox();
    private SMTPClient mSMTPClient = null;
    private SMTPServer mSMTPServer = null;

    private MailingFrame(JFrame mainFrame) {
        this.mainFrame = mainFrame;

        Logger.bindOutput(textArea_log);

        checkBox_client_needAuth.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                boolean en = checkBox_client_needAuth.isSelected();
                textField_client_userName.setEnabled(en);
                passwordField_client_password.setEnabled(en);
            }
        });
        checkBox_server_needAuth.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                boolean en = checkBox_server_needAuth.isSelected();
                textField_server_userName.setEnabled(en);
                textField_server_password.setEnabled(en);
            }
        });
        button_client_connect.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (checkBox_client_needAuth.isSelected()) {
                    mSMTPClient = new SMTPClient(textField_client_serverAddress.getText(),
                            textField_client_userName.getText(), String.copyValueOf(passwordField_client_password.getPassword()));
                } else {
                    mSMTPClient = new SMTPClient(textField_client_serverAddress.getText());
                }
            }
        });
        button_client_next.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mSMTPClient != null) {
                    mSMTPClient.nextStep();
                }
            }
        });
        button_client_send.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mSMTPClient != null) {
                    MailContent mailContent = new MailContent();
                    mailContent.from = textField_client_from.getText();
                    mailContent.to = textField_client_to.getText();
                    mailContent.subject = textField_client_subject.getText();
                    mailContent.content = textArea_client_mail.getText();
                    mSMTPClient.sendMail(mailContent);
                }
            }
        });
        button_client_disconnect.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mSMTPClient != null) {
                    mSMTPClient.quit();
                }
            }
        });
        button_server_start.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (checkBox_server_needAuth.isSelected()) {
                    mSMTPServer = new SMTPServer(mMailBox, textField_server_name.getText(),
                            textField_server_userName.getText(), textField_server_password.getText());
                } else {
                    mSMTPServer = new SMTPServer(mMailBox, textField_server_name.getText());
                }
            }
        });
        button_server_stop.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mSMTPServer != null) {
                    mSMTPServer.stop();
                }
            }
        });

        list_server_mail.setModel(listModel_mail);

        mMailBox.registerListener(this);
        list_server_mail.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                MailContent selectedMail = (MailContent) list_server_mail.getSelectedValue();
                if (selectedMail == null) {
                    textField_server_from.setText("");
                    textField_server_to.setText("");
                    textArea_server_mail.setText("");
                } else {
                    textField_server_from.setText(selectedMail.from);
                    textField_server_to.setText(selectedMail.to);
                    textArea_server_mail.setText(selectedMail.content);
                }
            }
        });
    }

    @Override
    public void onMailReceived(MailContent mail) {
        final ArrayList<MailContent> allMails = mMailBox.getAllMails();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                listModel_mail.clear();
                for (MailContent mail : allMails) {
                    listModel_mail.addElement(mail);
                }
            }
        });
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("SMTP Simulator");
        frame.setContentPane(new MailingFrame(frame).MainForm);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.pack();
        frame.setVisible(true);

        {
            int windowWidth = frame.getWidth();                    //获得窗口宽
            int windowHeight = frame.getHeight();                  //获得窗口高
            Toolkit kit = Toolkit.getDefaultToolkit();             //定义工具包
            Dimension screenSize = kit.getScreenSize();            //获取屏幕的尺寸
            int screenWidth = screenSize.width;                    //获取屏幕的宽
            int screenHeight = screenSize.height;                  //获取屏幕的高
            frame.setLocation(screenWidth / 2 - windowWidth / 2, screenHeight / 2 - windowHeight / 2);//设置窗口居中显示
        }
    }
}

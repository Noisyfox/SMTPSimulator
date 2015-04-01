/**
 * Created by Noisyfox on 2015/3/31.
 */
public class SMTPDefine {
    public static final int SERVER_READY = 220;
    public static final int OK = 250;
    public static final int WAIT_INPUT = 334;
    public static final int AUTH_SUCCESS = 235;
    public static final int CONNECT_CLOSE = 221;
    public static final int MAIL_START = 354;
    public static final int WRONG_SEQUENCE = 503;
    public static final int BAD_ARGUMENT = 501;
    public static final int UNKNOWN_CMD = 502;
    public static final int AUTH_FAILED = 454;


    public static final String LINE_SP = "\r\n";
}

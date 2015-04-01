/**
 * Created by Noisyfox on 2015/3/31.
 */
public class MailContent implements Cloneable {
    public String from;
    public String to;
    public String subject;
    public String content;

    @Override
    protected MailContent clone() {
        try {
            return (MailContent) super.clone();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public String toString() {
        return "mail from:" + from;
    }
}

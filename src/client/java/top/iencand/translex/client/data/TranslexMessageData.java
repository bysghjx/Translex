package top.iencand.translex.client.data;

import java.util.Scanner;

public class TranslexMessageData {
    public long lastTime;
    public int count;

    public TranslexMessageData(long time) {
        this.lastTime = time;
        this.count = 1;
    }
}
package com.tomtombridge.diagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Lightweight offline HR graph. Does not interpolate missing sensor data. */
public final class HeartChartView extends View {
    private final List<TtbinParser.Point> points;
    private final Paint line=new Paint(3);
    private final Paint axis=new Paint(3);
    private final boolean dark;
    private final float density;
    public HeartChartView(Context c,List<TtbinParser.Point> p,boolean isDark){
        super(c);points=new ArrayList<>(p);dark=isDark;density=getResources().getDisplayMetrics().density;
        line.setColor(dark?0xff75c8fb:0xff176fbd);line.setStrokeWidth(2.5f*density);line.setStyle(Paint.Style.STROKE);
        axis.setColor(dark?0xff59667c:0xffd9e1ec);axis.setStrokeWidth(density);
        setMinimumHeight((int)(100*density));
    }
    @Override protected void onMeasure(int w,int h){super.onMeasure(w,MeasureSpec.makeMeasureSpec((int)(100*density),MeasureSpec.EXACTLY));}
    @Override protected void onDraw(Canvas c){super.onDraw(c);
        int w=getWidth(),h=getHeight();if(w<8||h<8||points.size()<2)return;
        float left=8*density,right=w-8*density,top=8*density,bottom=h-12*density;
        c.drawLine(left,bottom,right,bottom,axis);
        long start=points.get(0).utcSeconds,end=points.get(points.size()-1).utcSeconds;
        if(end<=start)return;
        int min=220,max=40,valid=0;
        for(TtbinParser.Point p:points)if(p.heartRate!=null){min=Math.min(min,p.heartRate);max=Math.max(max,p.heartRate);valid++;}
        if(valid<2)return;min=Math.max(35,min-5);max=Math.min(225,max+5);if(max<=min)max=min+1;
        TtbinParser.Point prev=null;
        for(TtbinParser.Point p:points){
            if(p.heartRate!=null){if(prev!=null && p.utcSeconds-prev.utcSeconds<=3){
                float x1=left+(right-left)*(prev.utcSeconds-start)/(end-start),x2=left+(right-left)*(p.utcSeconds-start)/(end-start);
                float y1=bottom-(bottom-top)*(prev.heartRate-min)/(max-min),y2=bottom-(bottom-top)*(p.heartRate-min)/(max-min);
                c.drawLine(x1,y1,x2,y2,line);
            }prev=p;}else prev=null;
        }
    }
}

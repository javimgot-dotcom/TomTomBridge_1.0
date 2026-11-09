package com.tomtombridge.diagnostic;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Read-only parser. Record lengths are supplied by the TTBIN header, not hard-coded.
 * Conservative: rejects malformed/incomplete files, never invents GPS or HR readings.
 * Reference: TomTom Watch Interface v0.2, activity-file records (public reverse engineering).
 */
public final class TtbinParser {
    private TtbinParser() {}
    public static final class Point {
        public final long utcSeconds;
        public final double meters;
        public final int calories;
        public final Integer heartRate;
        public Point(long t, double d, int c, Integer hr) { utcSeconds=t; meters=d; calories=c; heartRate=hr; }
    }
    public static final class Activity {
        public final int ttbinVersion;
        public final long startUtc;
        public final int utcOffsetSeconds;
        public final int sportCode;
        public final String sport;
        public final long durationSeconds;
        public final double distanceMeters;
        public final int calories;
        public final int recordCount;
        public final Integer meanHeartRate;
        public final int validHeartSamples;
        public final List<Point> points;
        public final boolean hasGps;
        private Activity(int v,long start,int offset,int code,long duration,double dist,int kcal,int records,Integer avg,int n,List<Point> p,boolean gps) {
            ttbinVersion=v;startUtc=start;utcOffsetSeconds=offset;sportCode=code;sport=sportName(code);durationSeconds=duration;distanceMeters=dist;calories=kcal;recordCount=records;
            meanHeartRate=avg;validHeartSamples=n;points=p;hasGps=gps;
        }
    }
    private static int u16(byte[] b,int p){ return (b[p]&255)|((b[p+1]&255)<<8); }
    private static long u32(byte[] b,int p){ return ((long)b[p]&255)|(((long)b[p+1]&255)<<8)|(((long)b[p+2]&255)<<16)|(((long)b[p+3]&255)<<24); }
    private static int i32(byte[] b,int p){return (int)u32(b,p);}
    private static float f32(byte[] b,int p){return Float.intBitsToFloat(i32(b,p));}
    private static String sportName(int code) {
        // Public TomTom Watch Interface activity code table, validated 0x07 with supplied sample.
        switch(code) {
            case 0: return "Carrera";
            case 1: return "Ciclismo";
            case 2: return "Natación";
            case 7: return "Cinta de correr";
            case 8: return "Estilo libre";
            case 9: return "Gimnasio";
            case 10: return "Senderismo";
            case 11: return "Ciclismo interior";
            case 14: return "Carrera de montaña";
            case 15: return "Esquí";
            case 16: return "Snowboard";
            default: return "Deporte " + code;
        }
    }
    public static Activity parse(byte[] b) throws IllegalArgumentException {
        if(b==null||b.length<190||b.length>20*1024*1024|| (b[0]&255)!=0x20)throw new IllegalArgumentException("No es un TTBIN válido (cabecera)");
        int ver=b[1]&255;
        if(ver!=7 && ver!=9 && ver!=10)throw new IllegalArgumentException("Versión TTBIN sin soporte: " + ver);
        // For v7/9: 117 bytes of fixed header (from offset 0), then one count byte.
        // v10 header is different; conservative rejection until tested with a real file.
        if(ver==10)throw new IllegalArgumentException("Cabecera TTBIN v10 todavía sin validar");
        int entries=b[117]&255;
        if(entries<1||entries>100||118+entries*3>b.length)throw new IllegalArgumentException("Tabla de registros incompleta");
        Map<Integer,Integer> lengths=new HashMap<>();
        for(int i=0;i<entries;i++) {
            int j=118+3*i; int t=b[j]&255;int ln=u16(b,j+1);
            if(ln==0 || ln==0xffff || ln>4096)throw new IllegalArgumentException("Longitud de registro no compatible: 0x"+Integer.toHexString(t));
            lengths.put(t,ln);
        }
        long rawStart=u32(b,8); if(rawStart<946684800L||rawStart>4102444800L)throw new IllegalArgumentException("Fecha inicial no válida");
        int offset=i32(b,112);
        if(Math.abs((long)offset)>18*3600)offset=0;
        // This watch stores wall-clock seconds in the timestamp field, plus a local UTC offset.
        // Convert to real UTC once. The UI will then apply the device timezone.
        long start=rawStart-offset;
        int pos=118+3*entries, records=0, sport=0, kcal=0;
        long duration=0;
        double summaryMeters=Double.NaN,lastMeters=0;
        int hrSum=0, hrCount=0;
        boolean gps=false, summaryFound=false;
        Map<Long,Integer> hearts=new HashMap<>();
        List<long[]> hrRaw=new ArrayList<>();
        List<double[]> rawPoints=new ArrayList<>();
        while(pos<b.length){
            int tag=b[pos]&255; Integer len=lengths.get(tag);
            if(len==null||pos+len>b.length)throw new IllegalArgumentException("Registro truncado/desconocido en byte " +pos+" (0x"+Integer.toHexString(tag)+")");
            records++;
            if(tag==0x21 && len>=7) { if((b[pos+1]&255)==1) sport=b[pos+2]&255; }
            else if(tag==0x25 && len>=6){
                // Tag 0x25 layout: record type, HR value, quality/status byte, 4-byte clock.
                int h=b[pos+1]&255;
                long t=u32(b,pos+3);
                // 0 and 255 indicate no measurement; ignore implausible readings.
                if(h>=40 && h<=220){ hrSum+=h;hrCount++;hrRaw.add(new long[]{t-offset,h}); }
            }
            else if(tag==0x32 && len>=17){
                long t=u32(b,pos+1);double d=f32(b,pos+5);int c=u16(b,pos+9);
                if(t>=rawStart-60 && t<rawStart+86400 && Double.isFinite(d)&&d>=0&&d<1000000){
                    lastMeters=d;rawPoints.add(new double[]{t-offset,d,c});
                }
            }
            else if(tag==0x22 && len>=28){ gps=true; /* GPX/TXC location intentionally not guessed in v0.4 */ }
            else if(tag==0x27 && len>=12){
                sport=b[pos+1]&255;
                double d=f32(b,pos+2);
                if(Double.isFinite(d)&&d>=0&&d<1000000)summaryMeters=d;
                duration=u32(b,pos+6);kcal=u16(b,pos+10);summaryFound=true;
            }
            pos+=len;
        }
        if(pos!=b.length || records==0)throw new IllegalArgumentException("Archivo incompleto");
        if(!summaryFound)throw new IllegalArgumentException("Falta el resumen final del entrenamiento");
        if(duration<=0||duration>7*86400)throw new IllegalArgumentException("Duración fuera de rango");
        for(long[] h:hrRaw)hearts.put(h[0],(int)h[1]);
        List<Point> points=new ArrayList<>();
        for(double[] p:rawPoints) {
            long t=(long)p[0]; Integer hr=hearts.get(t);
            // Collapse repeated seconds: keep the most recent distance at that second.
            Point value=new Point(t,p[1],(int)p[2],hr);
            if(!points.isEmpty() && points.get(points.size()-1).utcSeconds==t) {
                Point prev=points.remove(points.size()-1);
                if(value.heartRate==null && prev.heartRate!=null)value=new Point(t,p[1],(int)p[2],prev.heartRate);
            }
            points.add(value);
        }
        // Timestamp of HR can differ by a second on some firmware; do not interpolate.
        double distance=Double.isFinite(summaryMeters)?summaryMeters:lastMeters;
        return new Activity(ver,start,offset,sport,duration,distance,kcal,records,hrCount>0?Math.round((float)hrSum/hrCount):null,hrCount,points,gps);
    }
    public static String localDate(Activity a) {
        SimpleDateFormat f=new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault());
        return f.format(new Date(a.startUtc*1000));
    }
    private static String utc(long s){
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(s*1000));
    }
    private static String dec(double n){return String.format(Locale.US,"%.2f",n);}
    /** TCX 2.0, without invented position or absent HR data. No direct upload to Strava. */
    public static byte[] toTcx(Activity a) {
        String sport=(a.sportCode==0||a.sportCode==7||a.sportCode==14)?"Running":(a.sportCode==1||a.sportCode==11)?"Biking":"Other";
        StringBuilder xml=new StringBuilder(1024+a.points.size()*280);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">\n")
           .append("  <Activities><Activity Sport=\"").append(sport).append("\"><Id>").append(utc(a.startUtc)).append("</Id>\n")
           .append("    <Lap StartTime=\"").append(utc(a.startUtc)).append("\"><TotalTimeSeconds>").append(a.durationSeconds).append("</TotalTimeSeconds>")
           .append("<DistanceMeters>").append(dec(a.distanceMeters)).append("</DistanceMeters>")
           .append("<Calories>").append(a.calories).append("</Calories><Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod>\n");
        xml.append("<Track>\n");
        for(Point p:a.points){
            xml.append("<Trackpoint><Time>").append(utc(p.utcSeconds)).append("</Time><DistanceMeters>").append(dec(p.meters)).append("</DistanceMeters>");
            if(p.heartRate!=null)xml.append("<HeartRateBpm><Value>").append(p.heartRate).append("</Value></HeartRateBpm>");
            xml.append("</Trackpoint>\n");
        }
        xml.append("</Track><Notes>Importado de TomTom TTBIN. ")
           .append(a.sportCode==7?"Entrenamiento de cinta, sin GPS.":a.sportCode==2?"Natación en piscina; sin coordenadas GPS.":"Datos sin posiciones GPS verificadas.")
           .append(" Distancia estimada por el reloj.</Notes></Lap></Activity></Activities></TrainingCenterDatabase>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }
}

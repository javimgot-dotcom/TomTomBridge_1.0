package com.tomtombridge.diagnostic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Personal-only Strava integration. Everything runs on the phone; no proxy or backend.
 * A user-supplied client secret is encrypted in Android Keystore-backed storage. This is
 * NOT an adequate secret-management scheme for distributing the APK to other users.
 */
public final class StravaSync {
    private static final String AUTH_URL="https://www.strava.com/oauth/mobile/authorize";
    private static final String TOKEN_URL="https://www.strava.com/oauth/token";
    private static final String API_URL="https://www.strava.com/api/v3/";
    private static final int CALLBACK_PORT=18765;
    private static final String REDIRECT="http://127.0.0.1:"+CALLBACK_PORT+"/callback";
    private static final String KEY_ALIAS="TomTomBridgePersonalStrava";
    private final Activity activity;
    private final Consumer<String> log;
    private final Runnable refresh;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private volatile ServerSocket callbackServer;
    private String expectedState;
    private volatile boolean uploadBusy;

    StravaSync(Activity activity,Consumer<String> log,Runnable refresh){
        this.activity=activity;this.log=log;this.refresh=refresh;
        this.prefs=activity.getSharedPreferences("personal_strava_v1",Activity.MODE_PRIVATE);
        expectedState=prefs.getString("oauth_state",null);
    }

    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(KEY_ALIAS))return (SecretKey)store.getKey(KEY_ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private void saveSecret(String name,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV(),enc=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] combined=new byte[iv.length+enc.length];System.arraycopy(iv,0,combined,0,iv.length);System.arraycopy(enc,0,combined,iv.length,enc.length);
        prefs.edit().putString(name,Base64.encodeToString(combined,Base64.NO_WRAP)).commit();
    }
    private String secret(String name) {
        try {
            String s=prefs.getString(name,null);if(s==null)return null;
            byte[] all=Base64.decode(s,Base64.NO_WRAP);if(all.length<29)return null;
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(all,12)));
            return new String(c.doFinal(all,12,all.length-12),StandardCharsets.UTF_8);
        }catch(Exception e){return null;}
    }
    private boolean configured(){return prefs.getString("client_id","").matches("[0-9]+") && secret("client_secret")!=null;}
    private boolean linked(){return secret("refresh_token")!=null;}
    void updateButton(Button button){button.setText(linked()?"Strava conectado · Gestionar":"Configurar y vincular Strava");}

    void connectOrDisconnect(){
        if(!configured()){showConfiguration();return;}
        if(linked()){
            new AlertDialog.Builder(activity).setTitle("Cuenta de Strava conectada")
                .setMessage("Puedes seguir sincronizando o desvincular la cuenta. Desvincular no elimina entrenamientos publicados en Strava.")
                .setPositiveButton("Continuar",(d,w)->{})
                .setNeutralButton("Cambiar credenciales",(d,w)->showConfiguration())
                .setNegativeButton("Desvincular",(d,w)->{
                    // Tokens are removed locally. To revoke remote authorization, use Strava settings.
                    prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply();
                    log.accept("Strava desvinculado del móvil. Para revocar acceso, revisa Ajustes de Strava → Mis aplicaciones.");refresh.run();
                }).show();
            return;
        }
        startAuthorization();
    }
    private void showConfiguration(){
        LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);
        int space=(int)(18*activity.getResources().getDisplayMetrics().density);
        layout.setPadding(space,0,space,0);
        EditText client=new EditText(activity);client.setHint("Client ID (número)");
        client.setInputType(InputType.TYPE_CLASS_NUMBER);client.setSingleLine(true);
        client.setText(prefs.getString("client_id",""));layout.addView(client);
        EditText secretInput=new EditText(activity);secretInput.setHint(secret("client_secret")==null?"Client Secret":"Client Secret (dejar vacío para conservar)");
        secretInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secretInput.setSingleLine(true);layout.addView(secretInput);
        new AlertDialog.Builder(activity).setTitle("Strava · uso personal")
            .setMessage("Crea una aplicación en strava.com/settings/api y configura el dominio de retorno como 127.0.0.1. Introduce las credenciales únicamente en el móvil. No se envían a GitHub.")
            .setView(layout).setNegativeButton("Cancelar",null)
            .setPositiveButton("Guardar y vincular",(d,w)->{
                String id=client.getText().toString().trim(),sec=secretInput.getText().toString().trim();
                if(!id.matches("[0-9]+")){log.accept("Strava: Client ID inválido.");return;}
                try {
                    if(!sec.isEmpty())saveSecret("client_secret",sec);
                    prefs.edit().putString("client_id",id).apply();
                    if(!configured()){log.accept("Introduce también tu Client Secret.");return;}
                    startAuthorization();
                }catch(Exception e){log.accept("No se pudieron guardar las credenciales protegidas.");}
            }).show();
    }
    private void startAuthorization(){
        if(!configured()){showConfiguration();return;}
        byte[] nonce=new byte[24];new SecureRandom().nextBytes(nonce);
        expectedState=Base64.encodeToString(nonce,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        prefs.edit().putString("oauth_state",expectedState).apply();
        final String state=expectedState;
        worker.execute(()->{
            try(ServerSocket server=new ServerSocket(CALLBACK_PORT,1,InetAddress.getByName("127.0.0.1"))){
                callbackServer=server;server.setSoTimeout(180000);
                main.post(()->{
                    Uri url=Uri.parse(AUTH_URL).buildUpon()
                        .appendQueryParameter("client_id",prefs.getString("client_id",""))
                        .appendQueryParameter("response_type","code")
                        .appendQueryParameter("redirect_uri",REDIRECT)
                        .appendQueryParameter("approval_prompt","auto")
                        .appendQueryParameter("scope","activity:write")
                        .appendQueryParameter("state",state).build();
                    try { activity.startActivity(new Intent(Intent.ACTION_VIEW,url)); }
                    catch(Exception e){log.accept("No se pudo abrir el navegador para autorizar Strava.");}
                });
                try(Socket sock=server.accept()){
                    sock.setSoTimeout(10000);
                    if(!sock.getInetAddress().isLoopbackAddress())throw new IllegalStateException("Conexión no local");
                    InputStream in=sock.getInputStream();ByteArrayOutputStream line=new ByteArrayOutputStream();
                    int c;while((c=in.read())!=-1 && c!='\n' && line.size()<4096){if(c!='\r')line.write(c);}
                    String request=line.toString("UTF-8");
                    String path=request.startsWith("GET ")?request.split(" ",3)[1]:"";
                    Uri callback=Uri.parse("http://127.0.0.1:"+CALLBACK_PORT+path);
                    boolean accepted=path.startsWith("/callback?") && state.equals(callback.getQueryParameter("state"))
                            && callback.getQueryParameter("code")!=null
                            && callback.getQueryParameter("scope")!=null
                            && Arrays.asList(callback.getQueryParameter("scope").split("[, ]+")).contains("activity:write");
                    String page=accepted?"Autorización recibida. Vuelve a TomTom Bridge.":"No se completó la autorización. Vuelve a TomTom Bridge.";
                    byte[] response=("<html><meta charset=\"utf-8\"><body style=\"font-family:sans-serif;padding:32px\"><h2>TomTom Bridge</h2><p>"+page+"</p></body></html>").getBytes(StandardCharsets.UTF_8);
                    OutputStream out=sock.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(response);out.flush();
                    if(!accepted){log.accept("Strava: autorización cancelada o permiso activity:write denegado.");return;}
                    redeemCode(callback.getQueryParameter("code"));
                }
            }catch(Exception ex){log.accept("Strava OAuth local: "+friendly(ex)+". Puedes repetir la vinculación.");}
            finally {callbackServer=null;}
        });
    }
    private void redeemCode(String code){
        try{
            JSONObject response=postForm(TOKEN_URL,"client_id="+encode(prefs.getString("client_id",""))+"&client_secret="+encode(secret("client_secret"))
                +"&code="+encode(code)+"&grant_type=authorization_code");
            storeTokens(response);
            prefs.edit().remove("oauth_state").apply();expectedState=null;
            log.accept("Strava vinculado correctamente. Las actividades válidas se subirán al sincronizar.");refresh.run();
        }catch(Exception e){log.accept("Strava: no se pudo completar la autorización: "+friendly(e));}
    }
    /** Optional compatibility with older deep link; intentionally does not accept secrets or codes from other apps. */
    void handleCallback(Uri ignored){}
    private void storeTokens(JSONObject data)throws Exception{
        String access=data.getString("access_token"),refreshToken=data.getString("refresh_token");
        if(access.isEmpty()||refreshToken.isEmpty())throw new IllegalArgumentException("tokens vacíos");
        saveSecret("access_token",access);saveSecret("refresh_token",refreshToken);
        prefs.edit().putLong("expires_at",data.optLong("expires_at",0)).commit();
    }
    private String accessToken()throws Exception{
        if(!linked())throw new IllegalStateException("Strava no vinculado");
        String token=secret("access_token");
        if(token!=null && System.currentTimeMillis()/1000+120<prefs.getLong("expires_at",0))return token;
        JSONObject r=postForm(TOKEN_URL,"client_id="+encode(prefs.getString("client_id",""))+"&client_secret="+encode(secret("client_secret"))
                +"&refresh_token="+encode(secret("refresh_token"))+"&grant_type=refresh_token");
        storeTokens(r);return secret("access_token");
    }
    String statusFor(File file){
        String s=prefs.getString("upload_"+file.getName(),"");
        if(s.startsWith("ok:"))return "✓ Publicado en Strava · "+s.substring(3);
        if(s.startsWith("processing:"))return "⏳ Strava está procesando el entrenamiento";
        if(s.startsWith("review:"))return "⚠ Verificar en Strava: "+s.substring(7);
        if(s.startsWith("error:"))return "⚠ Pendiente: "+s.substring(6);
        return linked()?"Pendiente de subir a Strava":"Strava sin vincular";
    }
    void enqueueAll(File folder){
        if(!linked())return;
        File[] files=folder.listFiles((d,n)->n.matches("[0-9A-Fa-f]{8}\\.ttbin"));
        if(files==null)return;
        Arrays.sort(files,(a,b)->a.getName().compareTo(b.getName()));
        for(File f:files)enqueueFile(f);
    }
    void enqueueFile(File file){
        if(!linked()||!file.isFile())return;
        String s=prefs.getString("upload_"+file.getName(),"");
        if(s.startsWith("ok:")||s.startsWith("review:"))return;
        worker.execute(()->upload(file));
    }
    private void status(File file,String state){prefs.edit().putString("upload_"+file.getName(),state).apply();refresh.run();}
    private void upload(File file){
        if(!linked())return;
        String previous=prefs.getString("upload_"+file.getName(),"");
        if(previous.startsWith("ok:")||previous.startsWith("review:"))return;
        try {
            String token=accessToken();
            JSONObject response;
            if(previous.startsWith("processing:")){
                response=api("GET","uploads/"+Uri.encode(previous.substring(11)),token,null,null);
            }else{
                byte[] raw=java.nio.file.Files.readAllBytes(file.toPath());
                TtbinParser.Activity a=TtbinParser.parse(raw);
                if(a.points.isEmpty()||a.sport.startsWith("Deporte "))throw new IllegalArgumentException("Formato de actividad sin validar");
                if(a.durationSeconds<=0)throw new IllegalArgumentException("Duración inválida");
                String sport=mapSport(a.sportCode);
                if(sport==null)throw new IllegalArgumentException("Deporte sin conversión fiable: "+a.sport);
                byte[] tcx=TtbinParser.toTcx(a);
                String boundary="ttbridge"+System.nanoTime();ByteArrayOutputStream body=new ByteArrayOutputStream();
                multipartField(body,boundary,"data_type","tcx");
                multipartField(body,boundary,"external_id","tomtom-"+hash(raw).substring(0,24)+".tcx");
                multipartField(body,boundary,"activity_type",sport);
                multipartField(body,boundary,"trainer",a.hasGps?"0":"1");
                multipartField(body,boundary,"name",a.sport+" · TomTom Bridge");
                body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+file.getName().replace(".ttbin",".tcx")+"\"\r\nContent-Type: application/xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(tcx);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
                // Unknown outcome (disconnect/timeout) must NOT trigger an automatic repeat upload.
                status(file,"review:subida iniciada");
                response=api("POST","uploads",token,body.toByteArray(),"multipart/form-data; boundary="+boundary);
            }
            if(response.optLong("activity_id",0)>0){status(file,"ok:"+response.getLong("activity_id"));log.accept("Strava: actividad publicada "+file.getName());return;}
            String error=response.optString("error","");
            if(!error.isEmpty() && !"null".equals(error))throw new IllegalArgumentException(error);
            String id=response.optString("id_str",String.valueOf(response.optLong("id",0)));
            if("0".equals(id)||id.isEmpty())throw new IllegalStateException("Strava no devolvió ID de carga");
            status(file,"processing:"+id);
            for(int i=0;i<7;i++){
                Thread.sleep(2000);
                JSONObject check=api("GET","uploads/"+Uri.encode(id),accessToken(),null,null);
                if(check.optLong("activity_id",0)>0){status(file,"ok:"+check.getLong("activity_id"));log.accept("Strava: actividad publicada "+file.getName());return;}
                String err=check.optString("error","");if(!err.isEmpty()&&!"null".equals(err))throw new IllegalArgumentException(err);
            }
            log.accept("Strava: actividad aceptada, pendiente de procesamiento: "+file.getName());
        }catch(Exception e){
            String old=prefs.getString("upload_"+file.getName(),"");
            // If a POST might have succeeded, do not blindly re-upload the same workout.
            if(old.startsWith("review:"))status(file,"review:resultado de envío desconocido");
            else if(old.startsWith("processing:"))log.accept("Strava: procesamiento pendiente; se consultará de nuevo.");
            else status(file,"error:"+friendly(e));
            log.accept("Strava: "+file.getName()+" · "+friendly(e));
        }
    }
    private static String mapSport(int code){
        switch(code){
            case 0:case 14:return "Run";
            case 7:return "VirtualRun";
            case 1:return "Ride";
            case 11:return "VirtualRide";
            case 2:return "Swim";
            case 8:case 9:return "Workout";
            case 10:return "Hike";
            default:return null;
        }
    }
    private static void multipartField(ByteArrayOutputStream b,String boundary,String name,String value)throws Exception{
        b.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static String encode(String x)throws Exception{return URLEncoder.encode(x==null?"":x,"UTF-8");}
    private static String hash(byte[] x)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(x);StringBuilder s=new StringBuilder();
        for(byte b:digest)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
    }
    private static String friendly(Exception e){
        String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.substring(0,Math.min(140,s.length()));
    }
    private static JSONObject postForm(String url,String form)throws Exception{
        return http("POST",url,null,form.getBytes(StandardCharsets.UTF_8),"application/x-www-form-urlencoded");
    }
    private static JSONObject api(String method,String route,String token,byte[] body,String type)throws Exception{
        return http(method,API_URL+route,token,body,type);
    }
    private static JSONObject http(String method,String url,String token,byte[] body,String contentType)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
        if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",contentType);
                try(OutputStream out=c.getOutputStream()){out.write(body);}}
            int status=c.getResponseCode();
            if(status>=300&&status<400)throw new IllegalStateException("Redirección HTTP inesperada");
            InputStream stream=status>=400?c.getErrorStream():c.getInputStream();
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(stream!=null)try(InputStream in=stream){byte[] buffer=new byte[2048];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>65536)throw new IllegalStateException("Respuesta demasiado grande");out.write(buffer,0,n);}}
            JSONObject json=new JSONObject(out.toString("UTF-8"));
            if(status>=400)throw new IllegalStateException("HTTP "+status+": "+json.optString("message",json.optString("error","Error API")));
            return json;
        }finally{c.disconnect();}
    }
}

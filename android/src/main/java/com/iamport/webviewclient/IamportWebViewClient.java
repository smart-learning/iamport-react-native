
package com.iamport.webviewclient;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.os.Build;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.net.URISyntaxException;

public class IamportWebViewClient extends WebViewClient {
  protected ThemedReactContext reactContext;
  protected Activity activity;

  private String userCode;
  private ReadableMap data;
  private String callback;
  private String triggerCallback;
  protected String appScheme;
  private ReadableMap loading;
  private Boolean loadingFinished = false;

  private static final String DEFAULT_REDIRECT_URL_WHEN_SUCCESS = "https://service.iamport.kr/payments/success";
  private static final String DEFAULT_REDIRECT_URL_WHEN_FAILURE = "https://service.iamport.kr/payments/fail";
  private static final String DEFAULT_REDIRECT_URL_WHEN_VBANK_SUCCESS = "https://service.iamport.kr/payments/vbank";

  private final static String BANKPAY = "kftc-bankpay";
  private final static String ISP = "ispmobile"; // ISP 모바일(ispmobile://TID=nictest00m01011606281506341724)
  private final static String PACKAGE_ISP = "kvp.jjy.MispAndroid320";
  private final static String PACKAGE_BANKPAY = "com.kftc.bankpay.android";

  public IamportWebViewClient(ThemedReactContext reactContext, Activity activity, ReadableMap param) {
    this.reactContext = reactContext;
    this.activity = activity;

    userCode = param.getString("userCode");
    data = param.getMap("data");
    callback = param.getString("callback");
    triggerCallback = param.getString("triggerCallback");
    appScheme = data.getString("app_scheme");
    loading = param.getMap("loading");
  }

  @Override
  public boolean shouldOverrideUrlLoading(WebView view, String url) {
    Log.i("url", url);

    if (isPaymentOver(url)) { // 결제시도가 종료된 후, 콜백이 설정되었으면, 리액트 네이티브로 event dispatch
      reactContext
        .getJSModule(RCTDeviceEventEmitter.class)
        .emit("message", url);

      return false;
    }
    if (isUrlStartsWithProtocol(url)) return false;

    Intent intent = null;
    try {
      intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME); // Intent URI 처리
      if (intent == null) return false;

      startNewActivity(intent.getDataString());
      return true;
    } catch(URISyntaxException e) {
      return false;
    } catch(ActivityNotFoundException e) { // PG사에서 호출하는 url에 package 정보가 없는 경우
      String scheme = intent.getScheme();
      if (isPaymentSchemeNotFound(scheme)) return true;

      String packageName = intent.getPackage();
      if (packageName == null) return false;

      startNewActivity("market://details?id=" + packageName);
      return true;
      }
  }

  /* WebView가 load되면 IMP.init, IMP.request_pay를 호출한다 */
  public void onPageFinished(WebView view, String url) {
    if (!loadingFinished && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) { // 무한루프 방지
      setCustomLoadingPage(view);
      
      view.evaluateJavascript("IMP.init('" + userCode + "');", null);
      view.evaluateJavascript("IMP.request_pay(" + toJSONObject(data) + ", " + triggerCallback + ");", null);

      loadingFinished = true;
    }
  }

  /* 커스텀 로딩화면 셋팅 */
  private void setCustomLoadingPage(WebView view) {
    String image = loading.getString("image");
    String message = loading.getString("message");

    view.evaluateJavascript("document.getElementById('imp-rn-img').src = '" + image + "';", null);
    view.evaluateJavascript("document.getElementById('imp-rn-msg').innerText = '" + message + "';", null);
  }

  /* ReadableMap을 JSONObject로 변경 */
  private JSONObject toJSONObject(ReadableMap data) { // https://gist.github.com/mfmendiola/bb8397162df9f76681325ab9f705748b
    JSONObject jsonObject = new JSONObject();

    try {
      ReadableMapKeySetIterator iterator = data.keySetIterator();

      while (iterator.hasNextKey()) {
        String key = iterator.nextKey();
        ReadableType type = data.getType(key);

        switch (type) {
          case Boolean:
            jsonObject.put(key, data.getBoolean(key));
            break;
          case Number:
            jsonObject.put(key, data.getDouble(key));
            break;
          case String:
            jsonObject.put(key, data.getString(key));
            break;
          case Map: // nested object recursive하게 처리
            jsonObject.put(key, toJSONObject(data.getMap(key)));
            break;
          case Array: // notice_url, card_quota
            jsonObject.put(key, toJSONArray(data.getArray(key)));
            break;
          default :
            jsonObject.put(key, data.getMap(key));
            break;
        }
      }
    } catch(JSONException e) {

    }

    return jsonObject;
  }

  private JSONArray toJSONArray(ReadableArray data) {
    JSONArray jsonArray = new JSONArray();

    try {
      for (int i = 0, size = data.size(); i < size; i++) {
        ReadableType type = data.getType(i);

        switch (type) {
          case Number: // card_quota
            jsonArray.put(i, data.getDouble(i));
            break;
          case String: // notice_url
            jsonArray.put(i, data.getString(i));
            break;
          default:
            jsonArray.put(i, data.getDynamic(i));
            break;
        }
      }
    } catch (JSONException e) {

    }

    return jsonArray;
  }

  /* url이 https, http 또는 javascript로 시작하는지 체크 */
  protected boolean isUrlStartsWithProtocol(String url) {
    if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("javascript:")) return true;
    return false;
  }

  /* 결제가 종료되었는지 여부를 판단한다 */
  protected boolean isPaymentOver(String url) {
    if (
      url.startsWith(DEFAULT_REDIRECT_URL_WHEN_FAILURE) ||
      url.startsWith(DEFAULT_REDIRECT_URL_WHEN_SUCCESS) ||
      url.startsWith(DEFAULT_REDIRECT_URL_WHEN_VBANK_SUCCESS) /* KG 이니시스, LG 유플러스, 나이스 가상계좌 발급성공 */
    ) return true;

    return false;
  }

  protected void startNewActivity(String parsingUri) {
    Uri uri = Uri.parse(parsingUri);
    Intent newIntent = new Intent(Intent.ACTION_VIEW, uri);

    activity.startActivity(newIntent);
  }

  /* ActivityNotFoundException에서 market 실행여부 확인 */
  protected boolean isPaymentSchemeNotFound(String scheme) {
    if (ISP.equalsIgnoreCase(scheme)) {
      startNewActivity("market://details?id=" + PACKAGE_ISP);
      return true;
    } else if (BANKPAY.equalsIgnoreCase(scheme)) {
      startNewActivity("market://details?id=" + PACKAGE_BANKPAY);
      return true;
    }

    return false;
  }

  /* 실시간 계좌이체 인증 후 후속처리 루틴 */
  public void bankPayPostProcess(int requestCode, int resultCode, Intent data) {}
}

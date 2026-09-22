package com.interviewedge.data;

import com.google.gson.JsonElement;
import okhttp3.*;
import retrofit2.Call;
import retrofit2.http.*;

public interface EdgeApi {
  @GET
  Call<JsonElement> get(@Header("Authorization") String auth, @Url String path);

  @POST
  Call<JsonElement> post(
      @Header("Authorization") String auth,
      @Url String path,
      @Header("Idempotency-Key") String key,
      @Body JsonElement body);

  @PATCH
  Call<JsonElement> patch(
      @Header("Authorization") String auth, @Url String path, @Body JsonElement body);

  @PUT
  Call<JsonElement> put(
      @Header("Authorization") String auth, @Url String path, @Body JsonElement body);

  @DELETE
  Call<JsonElement> delete(@Header("Authorization") String auth, @Url String path);

  @Multipart
  @POST("media")
  Call<JsonElement> upload(@Header("Authorization") String auth, @Part MultipartBody.Part file);

  @POST("me/exports")
  Call<ResponseBody> export(@Header("Authorization") String auth);
}

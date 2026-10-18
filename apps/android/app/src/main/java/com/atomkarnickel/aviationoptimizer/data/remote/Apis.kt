package com.atomkarnickel.aviationoptimizer.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface BackendApi {
    @GET("api/v1/meta")
    suspend fun meta(): MetaDto

    @POST("api/v1/search")
    suspend fun search(@Body body: SearchRequestDto): SearchResponseDto

    @POST("api/v1/search/{id}/rescore")
    suspend fun rescore(@Path("id") id: String, @Body body: RescoreRequestDto): SearchResponseDto

    @POST("api/v1/search/{id}/compare")
    suspend fun compare(@Path("id") id: String, @Body body: CompareRequestDto): CompareResponseDto
}

interface GitHubApi {
    @GET("repos/{owner}/{repo}/releases")
    suspend fun releases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30,
    ): List<GitHubReleaseDto>

    @GET
    suspend fun text(@Url url: String): okhttp3.ResponseBody
}

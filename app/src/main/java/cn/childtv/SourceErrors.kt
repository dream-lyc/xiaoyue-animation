package cn.childtv

import cn.childtv.core.SourceException
import cn.childtv.core.SourceFailure
import org.json.JSONObject

/** Upstream failures must not masquerade as a lost TV admin session or expose upstream bodies. */
internal object SourceErrors {
    fun response(error: SourceException): Pair<Int, JSONObject> {
        val message = when (error.reason) {
            SourceFailure.AUTHENTICATION -> "资源源认证失败或没有目录读取权限。请核对登录方式、用户名/密码或访问令牌及目录权限；启用双重验证时请使用访问令牌。"
            SourceFailure.NOT_FOUND -> "资源源地址或目录不存在。请检查服务根地址及已选目录；如经过反向代理，请确认 /api/fs 接口可访问。"
            SourceFailure.RATE_LIMITED -> "资源源请求过于频繁，请稍后重试，并检查 OpenList/AList 或反向代理的限流设置。"
            SourceFailure.INVALID_RESPONSE -> "资源源返回的数据格式不正确。请确认地址指向 OpenList/AList 或 WebDAV 服务，而非登录页、网盘分享页或代理错误页。"
            SourceFailure.RANGE_UNSUPPORTED -> "资源源不支持所需的范围读取。请检查服务及反向代理是否支持 Range 请求。"
            SourceFailure.NETWORK -> if (error.status != null) "资源源暂时无法处理请求（上游状态 ${error.status}）。请检查资源源及反向代理是否正常，再重试。" else "电视无法连接资源源。请检查资源地址、端口、电视网络和 HTTP/HTTPS 设置，再重试。"
        }
        val status = if (error.reason == SourceFailure.RATE_LIMITED) 503 else 502
        return status to JSONObject().put("message", message).put("code", "SOURCE_${error.reason.name}").also { body -> error.status?.let { body.put("upstreamStatus", it) } }
    }
}

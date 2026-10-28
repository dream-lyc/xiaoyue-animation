package cn.childtv.core

data class MetadataResult(val status:MatchStatus,val candidates:List<MetadataCandidate>,val fields:MetadataFields,val artwork:List<Artwork>,val diagnostics:List<String>,val retryAtMillis:Long?=null)
/** A failed provider leaves the asset available for manual maintenance. */
class MetadataPipeline(private val hashProvider:MovieHashProvider?=null,private val metadataProvider:MetadataProvider?=null) {
 fun match(source:SourceAdapter,file:SourceFile,nfo:NfoMetadata?=null,localArtwork:List<String> = emptyList(),manual:MetadataFields=MetadataFields(emptyMap()),confirmedIdentity:String?=null,confirmedNumber:EpisodeNumber?=null,seriesNfo:NfoMetadata?=null):MetadataResult {
  var retryAt:Long?=null
  val diagnostics=mutableListOf<String>();
  fun failed(error:MetadataServiceException){diagnostics+=error.reason;error.retryAtMillis?.let{retryAt=maxOf(retryAt?:it,it)}}
 val candidates=mutableListOf<MetadataCandidate>();val number=confirmedNumber?:nfo?.number?:FilenameParser.parse(file.path)
  var fields=manual.merge(buildMap{nfo?.title?.let{put("title",it)};nfo?.plot?.let{put("plot",it)};(seriesNfo?.title?:nfo?.seriesTitle)?.let{put("seriesTitle",it)}})
  if(confirmedIdentity!=null)candidates+=MetadataCandidate(confirmedIdentity,number,EvidenceKind.CONFIRMED_ID)
  (seriesNfo?.externalIds?.get("tmdb")?:nfo?.takeIf{it.number==null}?.externalIds?.get("tmdb"))?.takeIf{it.matches(Regex("[0-9]+"))}?.let{candidates+=MetadataCandidate("tmdb:$it",number,EvidenceKind.NFO_ID)}
  if(candidates.isEmpty()) {
   if(hashProvider?.configured==true && file.size>=131072)try {
    val hash=MovieHash.compute(file.size,source.openRange(file.path,0,65536),source.openRange(file.path,file.size-65536,65536))
    candidates+=hashProvider.search(hash,file.size)
   }catch(e:SourceException){diagnostics+="指纹不可用：${e.reason}"}catch(e:MetadataServiceException){failed(e)}
   else if(hashProvider?.configured!=true)diagnostics+="自动资料服务未配置" else diagnostics+="文件过小，指纹不可用"
  }
  if(candidates.isEmpty() && metadataProvider?.configured==true)try {
   val title=seriesNfo?.title?:nfo?.seriesTitle?:FilenameParser.seriesName(file.path).orEmpty()
   candidates+=metadataProvider.search(title).map{it.copy(number=number,evidence=EvidenceKind.NAME)}
  }catch(e:MetadataServiceException){failed(e)}
  var status=MetadataMatcher.classify(candidates,number)
  if(confirmedIdentity==null && FilenameParser.isMultiEpisode(file.path)){if(status==MatchStatus.MATCHED)status=MatchStatus.PENDING;diagnostics+="多集或合辑需要手工映射逻辑集时间区间"}
  val art=localArtwork.map{Artwork(it,ArtworkOrigin.LOCAL)}.toMutableList()
  if(status==MatchStatus.MATCHED) {
   val candidate=candidates.first()
   fields=fields.merge(buildMap {
    put("identity",candidate.identity)
    candidate.number?.season?.let{put("seasonNumber",it.toString())};candidate.number?.episode?.let{put("episodeNumber",it.toString())}
    candidate.seriesTitle?.let{put("seriesTitle",it)};candidate.title?.let{put("title",it)}
   })
   if(metadataProvider?.configured==true && candidate.identity.startsWith("tmdb:")) {
    try {fields=fields.merge(metadataProvider.series(candidate.identity).values)}catch(e:MetadataServiceException){failed(e)}
    if(candidate.number?.season!=null)try {
     fields=fields.merge(metadataProvider.episode(candidate.identity,candidate.number).values)
     if(art.isEmpty())art+=metadataProvider.artwork(candidate.identity,candidate.number)
    }catch(e:MetadataServiceException){failed(e)}
   }
  }
  return MetadataResult(status,candidates.distinct(),fields,art,diagnostics.distinct(),retryAt)
 }
}

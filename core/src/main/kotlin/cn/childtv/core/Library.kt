package cn.childtv.core

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
data class Library(val id:String=newId(),val name:String,val deleted:Boolean=false)
data class Series(val id:String=newId(),val libraryId:String,val title:String,val deleted:Boolean=false)
data class Season(val id:String=newId(),val seriesId:String,val number:Int)
data class Episode(val id:String=newId(),val seasonId:String,val number:Int,val title:String,val plot:String="",val deleted:Boolean=false)
data class EpisodeAsset(val episodeId:String,val assetId:String,val startMs:Long=0,val endMs:Long?=null) {
 init { require(startMs>=0 && (endMs==null || endMs>startMs)) }
 val durationMs:Long? get()=endMs?.minus(startMs)
}
data class Progress(val episodeId:String,val positionMs:Long,val completed:Boolean=false)

class LibraryCatalog {
 val libraries=linkedMapOf<String,Library>(); val series=linkedMapOf<String,Series>()
 val seasons=linkedMapOf<String,Season>(); val episodes=linkedMapOf<String,Episode>()
 val mappings=mutableListOf<EpisodeAsset>()
 @Synchronized fun previewMapping(seasonId:String,assetIds:List<String>,startNumber:Int=1):List<MappingPreview> {
  require(seasons.containsKey(seasonId) && startNumber>=0 && assetIds.all{it.isNotBlank()})
  return assetIds.mapIndexed{index,id->MappingPreview(seasonId,Math.addExact(startNumber,index),id)}
 }
 @Synchronized fun applyMapping(preview:List<MappingPreview>):List<String> {
  require(preview.all{seasons.containsKey(it.seasonId) && it.episodeNumber>=0 && it.assetId.isNotBlank() && it.startMs>=0 && (it.endMs==null || it.endMs>it.startMs)})
  val next=episodes.toMutableMap();val links=mappings.toMutableList();val ids=preview.map{row->
   val episode=next.values.firstOrNull{it.seasonId==row.seasonId && it.number==row.episodeNumber && !it.deleted}
    ?:Episode(seasonId=row.seasonId,number=row.episodeNumber,title=row.title?:"第${row.episodeNumber}集").also{next[it.id]=it}
   val mapping=EpisodeAsset(episode.id,row.assetId,row.startMs,row.endMs)
   if(mapping !in links)links+=mapping
   episode.id
  }
  episodes.clear();episodes.putAll(next);mappings.clear();mappings.addAll(links);return ids
 }
 @Synchronized fun renumber(numbers:Map<String,Int>) {
  require(numbers.all{(id,n)->episodes.containsKey(id) && n>=0})
  val next=episodes.mapValues{(id,e)->numbers[id]?.let{e.copy(number=it)}?:e}
  val live=next.values.filter{!it.deleted};require(live.map{it.seasonId to it.number}.distinct().size==live.size){"集号冲突"}
  episodes.clear();episodes.putAll(next)
 }
 fun select(seriesId:String?=null,seasonId:String?=null):Set<String> = episodes.values
  .filter { !it.deleted && (seasonId==null || it.seasonId==seasonId) && (seriesId==null || seasons[it.seasonId]?.seriesId==seriesId) }
  .sortedWith(compareBy({seasons[it.seasonId]?.seriesId},{seasons[it.seasonId]?.number},{it.number},{it.id})).map{it.id}.toCollection(linkedSetOf())
}
enum class TagOperation { APPEND,REMOVE,REPLACE }
class TagCatalog(initial:Map<String,Map<String,Set<String>>> = emptyMap()) {
 private var entries=initial.mapValues{(_,v)->v.mapValues{it.value.toSet()}}
 private val words=linkedMapOf<String,Set<String>>()
 val dictionary:Map<String,Set<String>> get()=synchronized(this){words.toMap()}
 @Synchronized fun initializeDefaults() { words["庆爸路线"]=(words["庆爸路线"]?:emptySet())+setOf("常规一","常规二","常规三") }
 @Synchronized fun values(episodeId:String,key:String):Set<String> = entries[episodeId]?.get(key).orEmpty()
 @Synchronized fun snapshot():Map<String,Map<String,Set<String>>> = entries.mapValues { it.value.toMap() }
 @Synchronized fun apply(episodeIds:Set<String>,key:String,values:Set<String>,operation:TagOperation):Int {
  require(key.isNotBlank() && values.all{it.isNotBlank()} && episodeIds.all{it.isNotBlank()})
  val next=entries.toMutableMap()
  episodeIds.forEach { id ->
   val tags=next[id].orEmpty().toMutableMap(); val old=tags[key].orEmpty()
   val result=when(operation){TagOperation.APPEND->old+values;TagOperation.REMOVE->old-values;TagOperation.REPLACE->values.toSet()}
   if(result.isEmpty())tags.remove(key) else tags[key]=result
   next[id]=tags
  }
  words[key]=words[key].orEmpty()+values; entries=next; return episodeIds.size
 }
 @Synchronized fun filter(ids:Set<String>,selection:Map<String,Set<String>>):Set<String> = ids.filterTo(linkedSetOf()){id->selection.all{(k,v)->v.isEmpty() || values(id,k).any{it in v}}}
}

data class MappingPreview(val seasonId:String,val episodeNumber:Int,val assetId:String,val startMs:Long=0,val endMs:Long?=null,val title:String?=null)

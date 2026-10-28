package cn.childtv.core
import kotlin.test.*
import java.time.LocalDate
class PolicyRecoveryTest {
 @Test fun dateRollbackRestoresOriginalConsumptionPromptAndConsumedExtraPermission() {
  val d=LocalDate.of(2026,10,27);val engine=ViewingPolicyEngine(60)
  engine.record(d,60);engine.markPromptHandled(d);engine.allowExtra(d,PlayingEpisode("extra",0,1000));engine.finish(d,"extra")
  engine.record(d.plusDays(1),15)
  val restored=ViewingPolicyEngine(60,engine.snapshot())
  assertEquals(60,restored.state(d).watchedSeconds);assertEquals(PolicyMode.LOCKED,restored.state(d).mode);assertFalse(restored.needsPrompt(d));assertFalse(restored.canStart(d,"extra",0));assertEquals(45,restored.remaining(d.plusDays(1)))
 }
}

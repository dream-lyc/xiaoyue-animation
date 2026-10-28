package cn.childtv.core
import kotlin.test.*
import java.time.LocalDate
class PolicySecurityTest {
 @Test fun extraEpisodeCannotBeRewoundOrRestartedAfterRecovery() {
  val d=LocalDate.of(2026,10,27);val e=ViewingPolicyEngine(0);e.allowExtra(d,"extra");assertTrue(e.canStart(d,"extra",0));e.advance(d,"extra",10000)
  val restored=ViewingPolicyEngine(0,e.snapshot());assertFalse(restored.canStart(d,"extra",0));assertTrue(restored.canStart(d,"extra",10000));restored.finish(d,"extra");assertFalse(restored.canStart(d,"extra",10000))
 }
 @Test fun newChallengeAndCancellationInvalidateOlderUnconsumedGrants() {
  val a=ParentAuthorization({0L},{lower,_->lower});val c=a.challenge(ParentAction.CHANGE_TODAY);val old=a.answer(c.id,2)!!
  val c2=a.challenge(ParentAction.EXTRA_EPISODE);assertFalse(a.consume(old,ParentAction.CHANGE_TODAY));val next=a.answer(c2.id,2)!!;a.cancel();assertFalse(a.consume(next,ParentAction.EXTRA_EPISODE))
 }
}

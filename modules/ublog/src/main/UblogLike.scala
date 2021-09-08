package lila.ublog

import cats.implicits._
import reactivemongo.api._
import scala.concurrent.ExecutionContext
import lila.db.dsl._
import lila.user.User
import org.joda.time.DateTime

final class UblogLike(colls: UblogColls)(implicit ec: ExecutionContext) {

  import UblogBsonHandlers._

  private def selectLiker(userId: User.ID) = $doc("likers" -> userId)

  def liked(post: UblogPost)(user: User): Fu[Boolean] =
    colls.post.exists($id(post.id) ++ selectLiker(user.id))

  def apply(postId: UblogPost.Id, user: User, v: Boolean): Fu[UblogPost.Likes] =
    fetchRankData(postId).flatMap {
      case None => fuccess(UblogPost.Likes(v ?? 1))
      case Some((prevLikes, liveAt, tier)) =>
        val likes = UblogPost.Likes(prevLikes.value + (if (v) 1 else -1))
        colls.post.update.one(
          $id(postId),
          $set(
            "likes" -> likes,
            "rank"  -> computeRank(likes, liveAt, tier)
          ) ++ {
            if (v) $addToSet("likers" -> user.id) else $pull("likers" -> user.id)
          }
        ) inject likes
    }

  def recomputeRankOfAllPosts(blogId: UblogBlog.Id): Funit =
    colls.blog.byId[UblogBlog](blogId.full) flatMap {
      _ ?? { blog =>
        colls.post
          .find($doc("blog" -> blog.id), $doc("likes" -> true, "lived" -> true).some)
          .cursor[Bdoc]()
          .list() flatMap { docs =>
          lila.common.Future.applySequentially(docs) { doc =>
            (
              doc.string("_id"),
              doc.getAsOpt[UblogPost.Likes]("likes"),
              doc.getAsOpt[UblogPost.Recorded]("lived")
            ).tupled ?? { case (id, likes, lived) =>
              colls.post.updateField($id(id), "rank", computeRank(likes, lived.at, blog.tier)).void
            }
          }
        }
      }
    }

  private def computeRank(likes: UblogPost.Likes, liveAt: DateTime, tier: UblogBlog.Tier) =
    UblogPost.Rank {
      liveAt plusHours {
        val baseHours = likesToHours(likes)
        tier match {
          case UblogBlog.Tier.LOW    => (baseHours * 0.3).toInt
          case UblogBlog.Tier.NORMAL => baseHours
          case UblogBlog.Tier.HIGH   => baseHours * 3
          case UblogBlog.Tier.BEST   => baseHours * 10
          case _                     => -99999
        }
      }
    }

  private def likesToHours(likes: UblogPost.Likes): Int =
    if (likes.value < 1) 0
    else (5 * math.log(likes.value) + 1).toInt.atMost(likes.value) * 12

  private def fetchRankData(postId: UblogPost.Id): Fu[Option[(UblogPost.Likes, DateTime, UblogBlog.Tier)]] =
    colls.post
      .aggregateOne() { framework =>
        import framework._
        Match($id(postId)) -> List(
          PipelineOperator($lookup.simple(colls.blog, "blog", "blog", "_id")),
          UnwindField("blog"),
          Project(
            $doc(
              "_id"       -> false,
              "blog.tier" -> true,
              "likes"     -> $doc("$size" -> "$likers"),
              "lived.at"  -> true
            )
          )
        )
      }
      .map { docOption =>
        for {
          doc    <- docOption
          likes  <- doc.getAsOpt[UblogPost.Likes]("likes")
          lived  <- doc.getAsOpt[Bdoc]("lived")
          liveAt <- lived.getAsOpt[DateTime]("at")
          blog   <- doc.getAsOpt[Bdoc]("blog")
          tier   <- blog int "tier"
        } yield (likes, liveAt, tier)
      }
}
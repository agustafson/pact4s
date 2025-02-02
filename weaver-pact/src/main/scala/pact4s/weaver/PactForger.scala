/*
 * Copyright 2021-2021 io.github.jbwheatley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pact4s.weaver

import au.com.dius.pact.consumer.BaseMockServer
import cats.effect.{Ref, Resource}
import cats.implicits._
import pact4s.PactForgerResources
import weaver.{MutableFSuite, TestOutcome}

trait SimplePactForger[F[_]] extends WeaverPactForgerBase[F] {
  override type Res = BaseMockServer
}

trait PactForger[F[_]] extends WeaverPactForgerBase[F] {
  type Resources
  override type Res = (Resources, BaseMockServer)

  def additionalSharedResource: Resource[F, Resources]

  override def sharedResource: Resource[F, (Resources, BaseMockServer)] =
    (additionalSharedResource, serverResource).tupled
}

trait WeaverPactForgerBase[F[_]] extends MutableFSuite[F] with PactForgerResources {
  private val F = effect

  private val testFailed: Ref[F, Boolean] = Ref.unsafe(false)

  private[weaver] val serverResource = Resource.make[F, BaseMockServer] {
    for {
      _ <- validatePactVersion.fold(F.unit)(F.raiseError)
      _ <- F.delay(server.start())
      _ <- F.delay(server.waitForServer())
    } yield server
  } { s =>
    testFailed.get.flatMap {
      case true =>
        logger.info(
          s"Not writing pacts for consumer ${pact.getConsumer} and provider ${pact.getProvider} to file because tests failed."
        )
        F.unit
      case false =>
        logger.info(
          s"Writing pacts for consumer ${pact.getConsumer} and provider ${pact.getProvider} to ${pactTestExecutionContext.getPactFolder}"
        )
        F.delay(s.verifyResultAndWritePact(null, pactTestExecutionContext, pact, mockProviderConfig.getPactVersion))
          .void
    } >>
      F.delay(s.stop())

  }

  override def spec(args: List[String]): fs2.Stream[F, TestOutcome] = super.spec(args).evalTap {
    case outcome if outcome.status.isFailed => testFailed.update(_ => true)
    case _                                  => F.unit
  }
}

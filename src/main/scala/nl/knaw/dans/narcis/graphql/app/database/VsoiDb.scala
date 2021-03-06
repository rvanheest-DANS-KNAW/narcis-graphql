/**
 * Copyright (C) 2019 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.narcis.graphql.app.database

import java.sql.{Connection, ResultSet}

import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import nl.knaw.dans.narcis.graphql.app.model.{ExternalPersonId, Person, PersonIdType}
import resource.{ManagedResource, managed}

import scala.util.{Success, Try}

class VsoiDb() extends DebugEnhancedLogging {

  // Note that this is person information without the external identifiers or works
  def getPerson(prsId: String)(implicit connection: Connection): Try[Option[Person]] = {
    trace(prsId)

    val query = "SELECT achternaam, email, url, voornaam, initialen, voorvoegsel, titulatuur FROM persoon WHERE pers_id=?;"
    val managedResultSet: ManagedResource[ResultSet] = for {
          prepStatement <- managed(connection.prepareStatement(query))
          _ = prepStatement.setString(1, prsId)
          resultSet <- managed(prepStatement.executeQuery())
        } yield resultSet

    // Note that we only get some fields, the rest is FAKE!
    managedResultSet.map(resultSet => {
      if (resultSet.next()) {
        val name = resultSet.getString("achternaam")
        val email = Option(resultSet.getString("email"))
        logger.info(s"Person info from database = name: $name, email: $email")
        val url = Option(resultSet.getString("url"))
        val givenname = Option(resultSet.getString("voornaam"))
        val initials = Option(resultSet.getString("initialen"))
        val prefix = Option(resultSet.getString("voorvoegsel"))
        val titles = Option(resultSet.getString("titulatuur"))

        Option(Person(prsId, name, email, url, givenname, initials, prefix, titles))
      } else {
        Option.empty
      }
    }).tried
  }


  def getAllPersons(implicit connection: Connection): Try[Seq[Person]] = {
    trace("")

    // TODO sort on pers_id; seems the most likely default, otherwise it would be achternaam
    val query = "SELECT pers_id, achternaam, email, url, voornaam, initialen, voorvoegsel, titulatuur FROM persoon ORDER BY achternaam, pers_id ASC;"
    val resultSet = for {
      prepStatement <- managed(connection.prepareStatement(query))
      resultSet <- managed(prepStatement.executeQuery())
    } yield resultSet


    resultSet.map(resultSet => {
      /*
       * Stream.continually(...).takeWhile(b => b) is equivalent to Java's while-loop: it checks
       * whether there is a next result and continues doing this until resultSet.next() returns false.
       */
      Stream.continually(resultSet.next())
        .take(1000) // just use hard limit for TESTING!
        .takeWhile(b => b)
        .map(_ => {
          val prsId = resultSet.getString("pers_id")
          val name = resultSet.getString("achternaam")
          val email = Option(resultSet.getString("email"))
          logger.info(s"Person info from database = name: $name, email: $email")
          val url = Option(resultSet.getString("url"))
          val givenname = Option(resultSet.getString("voornaam"))
          val initials = Option(resultSet.getString("initialen"))
          val prefix = Option(resultSet.getString("voorvoegsel"))
          val titles = Option(resultSet.getString("titulatuur"))
          Person(prsId, name, email, url, givenname, initials, prefix, titles)
        }
        )
        .toList // We can't leave it as a Stream, but need to convert to a List, since the resources are closed on calling .tried
    }
    ).tried
  }

  def getExternalIdentifiers(prsId: String)
                            (implicit connection: Connection): Try[Seq[ExternalPersonId]] = {
    trace(prsId)

    val query = "Select extern_id from persoon_externid where pers_id=?;"
    val resultSet = for {
      prepStatement <- managed(connection.prepareStatement(query))
      _ = prepStatement.setString(1, prsId)
      resultSet <- managed(prepStatement.executeQuery())
    } yield resultSet

    resultSet.map(resultSet => {
      /*
       * Stream.continually(...).takeWhile(b => b) is equivalent to Java's while-loop: it checks
       * whether there is a next result and continues doing this until resultSet.next() returns false.
       */
      Stream.continually(resultSet.next())
        .takeWhile(b => b)
        .flatMap(_ =>
          extPersIdfromVsoiIdString(resultSet.getString("extern_id"))
        )
        .toList // We can't leave it as a Stream, but need to convert to a List, since the resources are closed on calling .tried
    }
    ).tried
  }

  private def extPersIdfromVsoiIdString(identifier: String): Option[ExternalPersonId] = {
    getStrippedPersIdfromVsoiIdString(identifier)
      .flatMap(epid =>
        getNormalisedPersId(epid).doIfFailure {
          case e =>
            logger.warn(s"Ignoring VSOI pers id: (type, value) = ('${ epid.idType }', '${ epid.idValue }'), because it could not be converted to an external pers id: ${ e.getMessage }")
        }.toOption
      )
  }

  private def getStrippedPersIdfromVsoiIdString(identifier: String): Option[ExternalPersonId] = {
    // SysVsoi person identifier types; discovered by inspection of database output
    identifier.replaceAll("\n", "").trim.toLowerCase match {
      case dai if dai.startsWith("dai") => Some(ExternalPersonId(PersonIdType.dai_nl, dai.stripPrefix("dai")))
      case isni if isni.startsWith("isni") => Some(ExternalPersonId(PersonIdType.isni, isni.stripPrefix("isni")))
      case orcid if orcid.startsWith("orcid") => Some(ExternalPersonId(PersonIdType.orcid, orcid.stripPrefix("orcid")))
      case loop if loop.startsWith("loop") => Some(ExternalPersonId(PersonIdType.loop, loop.stripPrefix("loop")))
      case viaf if viaf.startsWith("viaf") => Some(ExternalPersonId(PersonIdType.viaf, viaf.stripPrefix("viaf")))
      case scopus if scopus.startsWith("scopus") => Some(ExternalPersonId(PersonIdType.scopus, scopus.stripPrefix("scopus")))
      case ror if ror.startsWith("ror") => Some(ExternalPersonId(PersonIdType.ror, ror.stripPrefix("ror")))
      case _ => None
    }
  }

  private def getNormalisedPersId(pid: ExternalPersonId): Try[ExternalPersonId] = {
    Success(pid) // no normalisation, assume vsoi is normalised, code can be found in narcis-pid-aggregator project
  }

}

package de.captaingoldfish.scim.sdk.keycloak.scim.handler.filtering.filtersetup;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import de.captaingoldfish.scim.sdk.common.constants.enums.Comparator;
import de.captaingoldfish.scim.sdk.common.constants.enums.SortOrder;
import de.captaingoldfish.scim.sdk.common.constants.enums.Type;
import de.captaingoldfish.scim.sdk.common.schemas.SchemaAttribute;
import de.captaingoldfish.scim.sdk.server.filter.AndExpressionNode;
import de.captaingoldfish.scim.sdk.server.filter.AttributeExpressionLeaf;
import de.captaingoldfish.scim.sdk.server.filter.FilterNode;
import de.captaingoldfish.scim.sdk.server.filter.NotExpressionNode;
import de.captaingoldfish.scim.sdk.server.filter.OrExpressionNode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


/**
 * @author Pascal Knueppel
 * @since 12.12.2022
 */
@Slf4j
@Getter
public abstract class AbstractFiltering<T>
{

  /**
   * this set is used to add left-joins to the JPQL query if joins are necessary. An example would be
   * filter-expression referencing the emails-table. In this case we need to add a left join on the
   * emails-table.
   */
  @Getter(AccessLevel.PROTECTED)
  protected final Set<JpqlTableJoin> joins = new HashSet<>();

  /**
   * scim attribute to start the entries to be retrieved at this index-1
   */
  protected final long startIndex;

  /**
   * the number of entries that should be returned
   */
  protected final int count;

  /**
   * the filter expression
   */
  protected final FilterNode filterNode;

  /**
   * the attribute that is used to sort the entries in a specific order
   */
  protected final SchemaAttribute sortBy;

  /**
   * the order in which the entries should be sorted. Default is ASCENDING
   */
  protected final SortOrder sortOrder;

  /**
   * used to build JPQL queries
   */
  protected final EntityManager entityManager;

  /**
   * the attribute mapping that is needed to build a valid JPQL query by mapping SCIM attributes to JPQL
   * attributes
   */
  protected final AbstractAttributeMapping attributeMapping;

  /**
   * the current realm on which the request is executed
   */
  protected final RealmModel realmModel;

  /**
   * this list is used to store the parameters that must be injected into the resulting JPQL query. If a JPQL
   * query was built like this:
   * 
   * <pre>
   *   select u from UserEntity u where u.realmId = :realmId and u.firstName = :firstname
   * </pre>
   * 
   * this list will have stored consumers that will resolve and set the specific parameters. We need this list
   * since we do not have the query instance at first. We will build the JPQL query string first and then
   * generate the query-instance. Just afterwards we will be able to add the parameter-values to the query.
   */
  private final List<Consumer<Query>> parameterResolverList = new ArrayList<>();

  public AbstractFiltering(KeycloakSession keycloakSession,
                           AbstractAttributeMapping attributeMapping,
                           long startIndex,
                           int count,
                           FilterNode filterNode,
                           SchemaAttribute sortBy,
                           SortOrder sortOrder)
  {
    this.attributeMapping = attributeMapping;
    this.entityManager = keycloakSession.getProvider(JpaConnectionProvider.class).getEntityManager();
    this.realmModel = keycloakSession.getContext().getRealm();
    this.startIndex = startIndex;
    this.count = count;
    this.filterNode = filterNode;
    this.sortBy = sortBy;
    this.sortOrder = sortOrder;
  }

  /**
   * gets the base entity on which the selection is started
   */
  protected abstract JpaEntityReferences getBaseEntity();

  /**
   * @return the basic restriction clause that makes sure that only the resources from the current realm are
   *         returned.
   */
  protected abstract String getRealmRestrictionClause();

  /**
   * parses the results from the database into valid Entity representations
   * 
   * @param resultStream the result from the database
   * @return the entities to return from the {@link #filterResources()} method
   */
  protected abstract List<T> parseResultStream(Stream<Object[]> resultStream);

  /**
   * will count the number of resources within the database based on the current filter expression
   * 
   * @return the total number of resources that would be returned based on the given filter details
   */
  public final long countResources()
  {
    final String jpqlQuery = getJpqlQueryString(true);

    log.info("Counting users from database with JPQL query:\n\t[{}]", jpqlQuery);

    Query query = entityManager.createQuery(jpqlQuery)
                               // startIndex start position is 1 as of SCIM specification but the database
                               // starts at position 0
                               .setFirstResult((int)startIndex - 1);

    getParameterResolverList().forEach(queryConsumer -> queryConsumer.accept(query));
    getParameterResolverList().clear();

    return (long)query.getSingleResult();
  }

  /**
   * will retrieve the resources within the database that match the given filter expression
   * 
   * @return the list of entities that matched the given filter expression
   */
  public final List<T> filterResources()
  {
    final String jpqlQuery = getJpqlQueryString(false);

    log.info("Reading users from database with JPQL query:\n\t[{}]", jpqlQuery);

    Query query = entityManager.createQuery(jpqlQuery)
                               // startIndex start position is 1 as of SCIM specification but the database
                               // starts at position 0
                               .setFirstResult((int)startIndex - 1)
                               .setMaxResults(count);

    getParameterResolverList().forEach(queryConsumer -> queryConsumer.accept(query));
    getParameterResolverList().clear();

    Stream<Object> results = query.getResultStream();

    return parseResultStream(results.parallel().map(o -> {
      if (o.getClass().isArray())
      {
        return (Object[])o;
      }
      else
      {
        return new Object[]{o};
      }
    }));
  }

  /**
   * builds the complete JPQL query string that is used to count and retrieve the resources
   * 
   * @param countResources if the given query is used for counting or retrieving resources
   * @return the JPQL query that can be executed with the {@link #entityManager}
   */
  @NotNull
  private String getJpqlQueryString(boolean countResources)
  {
    // we will start with the where clause since by analyzing the filter expression we will discover which joins
    // to other tables are necessary
    String whereClause = "";
    if (filterNode != null)
    {
      whereClause = getWhereClause();
    }

    final JpaEntityReferences baseEntity = getBaseEntity();
    final StringBuilder baseSelect = new StringBuilder();
    // @formatter:off
    baseSelect.append(countResources ? String.format("select count(%s)", baseEntity.getIdentifier())
                                     : String.format("select %s", baseEntity.getIdentifier()));
    // @formatter:on
    final StringBuilder fromClause = new StringBuilder(String.format("from %s %s",
                                                                     baseEntity.getTableName(),
                                                                     baseEntity.getIdentifier()));

    // add additional joins if necessary. Creating the where clause analyzed the filterNode-tree and while doing
    // this we are getting informed if additional joins to other tables are necessary. These joins will be added
    // now.
    final StringBuilder joinsString = new StringBuilder();
    for ( JpqlTableJoin join : getSortedJoins() )
    {
      if (!countResources && join.isJoinIntoSelection())
      {
        baseSelect.append(", ").append(join.getJoinTable().getIdentifier());
      }
      joinsString.append(" ").append(join.getJoinJpql());
    }

    return baseSelect + " " + fromClause + joinsString + whereClause + getOrderBy();
  }

  /**
   * the filter-expression is constructed by the client, and therefore we do not know in which order the
   * attributes in the filter expression are found and the necessary joins are identified. Therefore, we simply
   * put the joins into a {@link HashSet} in order to make sure that each join is done once at most and not
   * several times And this method will now bring the joins into an order so that the joins are parseable by
   * hibernate since the order in the JPQL string is essential for parameter resolving.
   * 
   * @return the list of joins how they should appear within the JPQL query
   */
  protected List<JpqlTableJoin> getSortedJoins()
  {
    List<JpqlTableJoin> sortedJoins = new ArrayList<>();
    for ( JpqlTableJoin join : joins )
    {
      if (sortedJoins.isEmpty())
      {
        sortedJoins.add(join);
        continue;
      }

      // if the join is done on the base entity it can be put in first position. For all tables that are directly
      // joined on the base-table an order is not required
      if (getBaseEntity().equals(join.getBaseTable()))
      {
        sortedJoins.add(0, join);
        continue;
      }

      // try to get the index of a join which join-table entry matches one within the sortedJoins. If we succeed in
      // finding one we will place
      boolean wasAdded = false;
      for ( int i = 0 ; i < sortedJoins.size() ; i++ )
      {
        JpqlTableJoin jpqlTableJoin = sortedJoins.get(i);
        // if the joins base-table matches a join already added within the sorted-joins list it must be added before
        // it
        if (join.getJoinTable().equals(jpqlTableJoin.getBaseTable()))
        {
          sortedJoins.add(i, join);
          wasAdded = true;
          break;
        }
      }

      if (!wasAdded)
      {
        sortedJoins.add(join);
      }
    }
    return sortedJoins;
  }

  /**
   * @return the where-clause of the JPQL-query based on the current filter expression and
   *         {@link #getRealmRestrictionClause()}
   */
  protected String getWhereClause()
  {
    final String filterExpression = StringUtils.stripToEmpty(getFilterExpression(filterNode));
    String effectiveFilterExpression = "";
    if (StringUtils.isNotBlank(filterExpression))
    {
      effectiveFilterExpression = String.format("and (%s)", filterExpression);
    }
    final String realmRestrictionClause = getRealmRestrictionClause();
    // should result in something like this:
    // where u.realmId = '${realm}' and u.serviceAccountClientLink is null
    // or
    // where u.realmId = '${realm}' and u.serviceAccountClientLink is null and (${filterExpression})
    return String.format(" where %s %s",
                         StringUtils.stripToEmpty(realmRestrictionClause),
                         StringUtils.stripToEmpty(effectiveFilterExpression));
  }

  /**
   * parses the SCIM filter node into a JPQL where-clause representation. The filternode is built in a logical
   * tree-like structure that makes it very easy to translate it into a valid JPQL where-expression
   * 
   * @param filterNode the SCIM filter expression in a tree-structure
   * @return the JPQL where filter-expression
   */
  protected String getFilterExpression(FilterNode filterNode)
  {
    if (filterNode == null)
    {
      return "";
    }

    if (filterNode instanceof AndExpressionNode)
    {
      AndExpressionNode andExpressionNode = (AndExpressionNode)filterNode;
      return "(" + getFilterExpression(andExpressionNode.getLeftNode()) + " AND "
             + getFilterExpression(andExpressionNode.getRightNode()) + ")";
    }
    else if (filterNode instanceof OrExpressionNode)
    {
      OrExpressionNode orExpressionNode = (OrExpressionNode)filterNode;
      return "(" + getFilterExpression(orExpressionNode.getLeftNode()) + " OR "
             + getFilterExpression(orExpressionNode.getRightNode()) + ")";
    }
    else if (filterNode instanceof NotExpressionNode)
    {
      NotExpressionNode notExpressionNode = (NotExpressionNode)filterNode;
      return "NOT (" + getFilterExpression(notExpressionNode.getRightNode()) + ")";
    }
    else
    {
      AttributeExpressionLeaf attributeExpressionLeaf = (AttributeExpressionLeaf)filterNode;
      boolean isCaseExact = attributeExpressionLeaf.getSchemaAttribute().isCaseExact();
      final String fullResourceName = attributeExpressionLeaf.getSchemaAttribute().getFullResourceName();

      FilterAttribute filterAttribute = attributeMapping.getAttribute(fullResourceName);
      {
        // basically informs the method {@link #getJpqlQueryString()} that an additional left-join is required
        for ( JpqlTableJoin join : filterAttribute.getJoins() )
        {
          // only add the joins that are not the base-table itself. For users this would be the UserEntity-join.
          // Since the UserEntity is already the base-table we do not need to do another join onto this table
          if (join.getJoinTable() != null)
          {
            joins.add(join);
          }
        }
      }

      final String jpqlAttribute = toCaseCheckedValue(attributeExpressionLeaf.getType(),
                                                      isCaseExact,
                                                      filterAttribute.getJpqlMapping());
      final String comparisonExpression = resolveComparator(attributeExpressionLeaf);
      return String.format("%s %s ", jpqlAttribute, comparisonExpression);
    }
  }

  /**
   * translates the current attribute comparison into its JPQL representation and adds parameters instead of
   * direct values into the JPQL query. The parameters will be added into the {@link #parameterResolverList}
   * which will then later be added as Query-parameter with JPA in order to prevent SQL-injections.
   * 
   * @param attributeExpressionLeaf the SCIM attribute-filter-expression that should resolve to something like
   * 
   *          <pre>
   *             u.userName = :aac0c224621adc44a29f6ddd619b5b12a6
   *          </pre>
   * 
   *          where the string "ac0c224621adc44a29f6ddd619b5b12a6" represents the parameter name
   * @return the JPQL attribute comparison string
   */
  private String resolveComparator(AttributeExpressionLeaf attributeExpressionLeaf)
  {
    final Comparator comparator = attributeExpressionLeaf.getComparator();
    final String parameterName = "a" + UUID.randomUUID().toString().replaceAll("-", "");

    boolean isCaseExact = attributeExpressionLeaf.getSchemaAttribute().isCaseExact();
    final String jpqlParameter = toCaseCheckedValue(attributeExpressionLeaf.getType(),
                                                    isCaseExact,
                                                    ":" + parameterName);

    switch (comparator)
    {
      case EQ: // equals
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "= " + jpqlParameter;
      case NE: // not equals
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "!= " + jpqlParameter;
      case CO: // contains
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "like concat('%', " + jpqlParameter + ", '%')";
      case SW: // start with
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "like concat(" + jpqlParameter + ", '%')";
      case EW: // ends with
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "like concat('%', " + jpqlParameter + ")";
      case GE: // greater equals
        setParameterValue(attributeExpressionLeaf, parameterName);
        return ">= " + jpqlParameter;
      case LE: // lower equals
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "<= " + jpqlParameter;
      case GT: // greater than
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "> " + jpqlParameter;
      case LT: // lower than
        setParameterValue(attributeExpressionLeaf, parameterName);
        return "< " + jpqlParameter;
      default: // is "PR" = present
        return "is not null";
    }
  }

  /**
   * builds the parameter-resolver that will later be put into the JPA-query instance after the JPQL query
   * string was parsed by hibernate into a {@link Query} instance.
   * 
   * @param attributeExpressionLeaf the SCIM attribute-filter-expression that contains the value that must be
   *          added as parameter to the query
   * @param parameterName the parameter-name from the JPQL-query-string
   */
  private void setParameterValue(AttributeExpressionLeaf attributeExpressionLeaf, String parameterName)
  {

    switch (attributeExpressionLeaf.getType())
    {
      case BOOLEAN:
        boolean booleanValue = attributeExpressionLeaf.getBooleanValue().orElse(false);
        parameterResolverList.add(query -> {
          log.debug("sql param: '{}' with value: '{}'", parameterName, booleanValue);
          query.setParameter(parameterName, booleanValue);
        });
        break;
      case DATE_TIME:
        Instant instant = attributeExpressionLeaf.getDateTime().orElse(null);
        parameterResolverList.add(query -> {
          log.debug("sql param: '{}' with value: '{}'", parameterName, instant);
          query.setParameter(parameterName, instant.toEpochMilli());
        });
        break;
      case INTEGER:
        final Long longValue = attributeExpressionLeaf.getNumberValue().map(BigDecimal::longValue).orElse(null);
        parameterResolverList.add(query -> {
          log.debug("sql param: '{}' with value: '{}'", parameterName, longValue);
          query.setParameter(parameterName, longValue);
        });
        break;
      default:
        final String stringValue = attributeExpressionLeaf.getValue();
        parameterResolverList.add(query -> {
          log.debug("sql param: '{}' with value: '{}'", parameterName, stringValue);
          query.setParameter(parameterName, stringValue);
        });
        break;
    }
  }

  /**
   * if a SCIM attribute is defined as not case exact the database attributes will be converted into lower case
   * to enable a case-insensitive search
   *
   * @param type lower case makes only sense for string-type values
   * @param isCaseExact if the attribute should be compared case-insensitive or not
   * @param jpqlParameterName the name of the jpql-parameter e.g. "u.userName"
   * @return the unchanged parameter if a case-sensitive check is required and the parameter surrounded by
   *         "lower(...)" if the check should be case-insensitive
   */
  private String toCaseCheckedValue(Type type, boolean isCaseExact, String jpqlParameterName)
  {
    final boolean isNotStringType = !Type.STRING.equals(type) && !Type.REFERENCE.equals(type);
    if (isCaseExact || isNotStringType)
    {
      return jpqlParameterName;
    }
    else
    {
      return String.format("lower(%s)", jpqlParameterName);
    }
  }

  /**
   * @return the SCIM attributes translated into a JPQL order by expression
   */
  protected String getOrderBy()
  {
    if (sortBy == null)
    {
      return "";
    }
    final String sortByAttributeName = attributeMapping.getAttribute(sortBy.getName()).getJpqlMapping();
    final SortOrder effectiveOrder = Optional.ofNullable(sortOrder).orElse(SortOrder.ASCENDING);
    final String sortOrderString = effectiveOrder == SortOrder.ASCENDING ? "asc" : "desc";
    return sortOrder == null ? "" : String.format("order by %s %s", sortByAttributeName, sortOrderString);
  }

}
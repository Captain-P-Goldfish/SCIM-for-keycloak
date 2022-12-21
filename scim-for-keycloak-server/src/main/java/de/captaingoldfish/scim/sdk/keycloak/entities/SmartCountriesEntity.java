package de.captaingoldfish.scim.sdk.keycloak.entities;

import java.util.Optional;

import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.keycloak.models.utils.KeycloakModelUtils;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * @author Pascal Knueppel
 * @since 17.12.2022
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "USER_SCIM_COUNTRIES_EXT")
public class SmartCountriesEntity
{

  /**
   * primary key
   */
  @Id
  @Column(name = "ID")
  @Access(AccessType.PROPERTY) // we do this because relationships often fetch id, but not entity. This avoids an extra
  // SQL
  @Setter(AccessLevel.PROTECTED)
  private String id = KeycloakModelUtils.generateId();

  /**
   * reverse mapping for JPA
   */
  @EqualsAndHashCode.Exclude
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "SCIM_ATTRIBUTES_ID")
  private ScimUserAttributesEntity userAttributes;

  /**
   * name of the country
   */
  @Column(name = "COUNTRY")
  private String country;

  @Builder
  public SmartCountriesEntity(String id, ScimUserAttributesEntity userAttributes, String country)
  {
    this.id = Optional.ofNullable(id).orElse(KeycloakModelUtils.generateId());
    this.userAttributes = userAttributes;
    this.country = country;
  }
}
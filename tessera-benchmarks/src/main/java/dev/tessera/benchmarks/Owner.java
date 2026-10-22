package dev.tessera.benchmarks;

import dev.tessera.runtime.TemplateView;
import java.util.List;

/** An owner, modelled on spring-petclinic's. A JavaBean so both engines read the same getters. */
@TemplateView
public final class Owner {
  private final int id;
  private final String firstName;
  private final String lastName;
  private final String address;
  private final String city;
  private final String telephone;
  private final boolean active;
  private final List<Pet> pets;

  public Owner(
      int id,
      String firstName,
      String lastName,
      String address,
      String city,
      String telephone,
      boolean active,
      List<Pet> pets) {
    this.id = id;
    this.firstName = firstName;
    this.lastName = lastName;
    this.address = address;
    this.city = city;
    this.telephone = telephone;
    this.active = active;
    this.pets = List.copyOf(pets);
  }

  public int getId() {
    return id;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getAddress() {
    return address;
  }

  public String getCity() {
    return city;
  }

  public String getTelephone() {
    return telephone;
  }

  public boolean isActive() {
    return active;
  }

  public List<Pet> getPets() {
    return pets;
  }
}

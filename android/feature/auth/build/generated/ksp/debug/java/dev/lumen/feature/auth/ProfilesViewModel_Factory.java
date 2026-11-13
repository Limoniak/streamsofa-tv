package dev.lumen.feature.auth;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import dev.lumen.data.AccountsRepository;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class ProfilesViewModel_Factory implements Factory<ProfilesViewModel> {
  private final Provider<AccountsRepository> accountsProvider;

  private ProfilesViewModel_Factory(Provider<AccountsRepository> accountsProvider) {
    this.accountsProvider = accountsProvider;
  }

  @Override
  public ProfilesViewModel get() {
    return newInstance(accountsProvider.get());
  }

  public static ProfilesViewModel_Factory create(Provider<AccountsRepository> accountsProvider) {
    return new ProfilesViewModel_Factory(accountsProvider);
  }

  public static ProfilesViewModel newInstance(AccountsRepository accounts) {
    return new ProfilesViewModel(accounts);
  }
}

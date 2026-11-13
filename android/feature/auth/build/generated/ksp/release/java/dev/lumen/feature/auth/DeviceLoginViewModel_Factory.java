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
public final class DeviceLoginViewModel_Factory implements Factory<DeviceLoginViewModel> {
  private final Provider<AccountsRepository> accountsProvider;

  private DeviceLoginViewModel_Factory(Provider<AccountsRepository> accountsProvider) {
    this.accountsProvider = accountsProvider;
  }

  @Override
  public DeviceLoginViewModel get() {
    return newInstance(accountsProvider.get());
  }

  public static DeviceLoginViewModel_Factory create(Provider<AccountsRepository> accountsProvider) {
    return new DeviceLoginViewModel_Factory(accountsProvider);
  }

  public static DeviceLoginViewModel newInstance(AccountsRepository accounts) {
    return new DeviceLoginViewModel(accounts);
  }
}

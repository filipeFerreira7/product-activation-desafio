package br.com.filipe.product_activation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductActivationApplicationTests {

	@Test
	void applicationClassIsLoadable() {
		assertThat(ProductActivationApplication.class).isNotNull();
	}
}
